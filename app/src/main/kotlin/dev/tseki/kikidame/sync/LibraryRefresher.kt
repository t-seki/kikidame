package dev.tseki.kikidame.sync

import android.util.Log
import dev.tseki.kikidame.di.ApplicationScope
import dev.tseki.kikidame.domain.AppSettingsRepository
import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.LibraryRefreshRepository
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.RefreshResult
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.download.DownloadKicker
import dev.tseki.kikidame.R
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 更新と同期の唯一の入口。同時に 1 つしか走らせず、結果を文言にして画面へ流す。
 *
 * - [refresh]: 全走査 = ライブラリ全体の同期（ADR 0004）。番組一覧の「引っ張って更新」、定期・起動時の Worker が呼ぶ
 * - [syncProgram]: 1 番組の同期。シートの「この番組を今すぐ同期」が呼ぶ
 * - [refreshProgram]: 番組単位の取り込み（#12）。各回一覧の「引っ張って更新」が呼ぶ。削除しない
 * - 「Wi-Fi のみ」は手動を含めて効く。従量制ならサーバに触らず理由を出して終える。手元のファイルの整合（#5）はその前に走る
 * - 401 はログアウトと同じ処理をする（セッション状態が変わり、画面側が接続画面へ導く）
 * - 定期・起動時の silent な同期ではクルクルを出さない。その最中に手動の操作が来たら、走っている同期に合流する（#134）
 * - silent な同期の間は [isSyncingInBackground] を立てる（画面は細いバーを出す。#138）。合流したらクルクルに切り替える
 */
@Singleton
class LibraryRefresher @Inject constructor(
    private val refreshRepository: LibraryRefreshRepository,
    private val sessionRepository: SessionRepository,
    private val downloads: DownloadRepository,
    private val settings: AppSettingsRepository,
    private val network: NetworkStatus,
    private val nowPlaying: NowPlaying,
    private val kicker: DownloadKicker,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val lock = Any()

    /** 走っている実行。無ければ null。[lock] の中で読み書きする。 */
    private var running: Run? = null

    private val _isRefreshing = MutableStateFlow(false)

    /** 手動の実行と、手動が合流した silent な実行の間だけ true。silent なだけの実行では立てない（#134）。 */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _isSyncingInBackground = MutableStateFlow(false)

    /** silent な実行の間だけ true。手動が合流したら false に戻し、[isRefreshing] に譲る（両方は立てない。#138）。 */
    val isSyncingInBackground: StateFlow<Boolean> = _isSyncingInBackground

    // 連続した更新の結果を取りこぼさないよう少し余裕を持ち、溢れたら古い方を捨てる
    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<UiText> = _messages

    /** 画面が消えても最後まで走らせたいとき（ライブラリ選択直後の初回取得）。 */
    fun launchRefresh() {
        applicationScope.launch { refresh() }
    }

    /**
     * 全走査して同期する。[silent] ならクルクルも文言も出さず（定期・起動時）、代わりに [isSyncingInBackground] を立てて細いバーを出す（#138）。
     * ただし手動の操作が合流したら、そこからバーを消してクルクルを出し、結果の文言を出す。
     */
    suspend fun refresh(silent: Boolean = false): RefreshResult? = guarded(silent) {
        refreshRepository.refresh(excluded = excluded()).also { report(it.toSyncMessage()) }
    }

    /** 1 番組だけ取り込む。サーバ ID の無い番組は全走査（同期）にフォールバックする。 */
    suspend fun refreshProgram(programId: ProgramId): RefreshResult? = guarded(silent = false) {
        val result = refreshRepository.refreshProgram(programId)
        if (result != null) {
            report(UiText.Plural(R.plurals.sync_program_fetched, result.episodes))
            result
        } else {
            refreshRepository.refresh(excluded = excluded()).also { report(it.toSyncMessage()) }
        }
    }

    /** 1 番組だけ同期する。サーバ ID の無い番組は何もしない（シート側でスイッチを無効にしている）。 */
    suspend fun syncProgram(programId: ProgramId): RefreshResult? = guarded(silent = false) {
        refreshRepository.syncProgram(programId, excluded = excluded())?.also { report(it.toProgramSyncMessage()) }
    }

    /** 聴いている回は削除から外す。ただし聴き終えて止まっている回は「再生済みなら削除」に任せる（#27）。 */
    private fun excluded(): Set<EpisodeId> = setOfNotNull(nowPlaying.excludedFromSync)

    /** 1 回の実行。手動の操作が合流すると [silent] が外れ、結果の文言が出る。 */
    private inner class Run(silent: Boolean) {
        /** [silent] と [unreported] は [lock] の中で読み書きする。 */
        var silent: Boolean = silent

        /** silent なうちに出しそびれた結果の文言。合流した時点で出す。 */
        var unreported: UiText? = null

        /** 合流した呼び出しが待つ、この実行の結果。 */
        val result = CompletableDeferred<RefreshResult?>()

        /** 途中の文言（手元の整合）。silent なら捨てる。 */
        fun say(message: UiText) {
            synchronized(lock) {
                if (!silent) _messages.tryEmit(message)
            }
        }

        /**
         * 結果の文言（成功・エラー・Wi-Fi のみ）。1 回の実行で 1 回だけ呼ぶ。
         * silent なら取っておき、この後の片付けまでに手動が合流したらそこで出す。
         */
        fun report(message: UiText) {
            synchronized(lock) {
                if (silent) unreported = message else _messages.tryEmit(message)
            }
        }
    }

    /**
     * 同時に 1 つしか走らせない。走っている間に来た呼び出しは:
     * - silent な実行の最中の手動 → 合流する。クルクルを出し、結果の文言を出させ、その実行の結果を待って返す（全走査をやり直さない）
     * - それ以外 → 何もせず null
     */
    private suspend fun guarded(silent: Boolean, block: suspend Run.() -> RefreshResult?): RefreshResult? {
        var joined: Run? = null
        val run = synchronized(lock) {
            val current = running
            when {
                current == null -> Run(silent).also {
                    running = it
                    if (silent) _isSyncingInBackground.value = true else _isRefreshing.value = true
                }
                !silent && current.silent -> {
                    current.silent = false
                    current.unreported?.let { _messages.tryEmit(it) }
                    current.unreported = null
                    _isSyncingInBackground.value = false
                    _isRefreshing.value = true
                    joined = current
                    null
                }
                else -> null
            }
        }
        joined?.let { return it.result.await() }
        if (run == null) return null
        var result: RefreshResult? = null
        try {
            result = run.execute(block)
            return result
        } finally {
            synchronized(lock) {
                running = null
                _isRefreshing.value = false
                _isSyncingInBackground.value = false
            }
            run.result.complete(result)
        }
    }

    private suspend fun Run.execute(block: suspend Run.() -> RefreshResult?): RefreshResult? {
        try {
            if (sessionRepository.state.first() !is SessionState.Ready) return null
            // 手元のファイルが消えていないか先に整合する（#5）。ローカル I/O なのでネットワークの条件は見ない
            val reconciled = downloads.reconcileMissingFiles()
            if (reconciled > 0) say(UiText.Plural(R.plurals.sync_reconciled, reconciled))
            if (settings.wifiOnly.first() && network.isMetered()) {
                report(UiText.Res(R.string.sync_not_on_wifi))
                return null
            }
            val result = block() ?: return null
            // 同期は済んでいる。Worker を起こせなくても結果は返す（次の起動やダウンロード操作で拾われる）
            if (result.enqueued > 0) {
                try {
                    kicker.kick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "could not start the download worker", e)
                }
            }
            return result
        } catch (e: ServerException.Unauthorized) {
            // ログアウトすると画面が接続画面へ移るので、文言を先に出す
            report(UiText.Res(R.string.sync_error_session_expired))
            sessionRepository.signOut()
            return null
        } catch (e: ServerException.Unreachable) {
            report(UiText.Res(R.string.sync_error_unreachable))
            return null
        } catch (e: ServerException.Failed) {
            report(UiText.Res(R.string.sync_error_fetch_failed, e.message.orEmpty()))
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Room / DataStore / Keystore の失敗。落とさずに文言にする
            Log.e(TAG, "refresh failed", e)
            report(UiText.Res(R.string.sync_error_import_failed, e::class.simpleName.orEmpty()))
            return null
        }
    }

    private companion object {
        const val TAG = "LibraryRefresher"
    }
}

/** 「N 回をダウンロード予約、M 回を削除」。0 のものは省く。何も無ければ null。区切りは [R.string.common_list_separator]。 */
private fun RefreshResult.actionsText(): UiText? {
    val actions = listOfNotNull(
        enqueued.takeIf { it > 0 }?.let { UiText.Plural(R.plurals.sync_enqueued, it) },
        (deleted + removed).takeIf { it > 0 }?.let { UiText.Plural(R.plurals.sync_deleted, it) },
    )
    return actions.takeIf { it.isNotEmpty() }?.let { UiText.Joined(it, UiText.Res(R.string.common_list_separator)) }
}

/** 文を「。」（言語ごとの [R.string.sync_sentence_separator]）でつなぐ。 */
private fun sentences(vararg parts: UiText?): UiText =
    UiText.Joined(parts.filterNotNull(), UiText.Res(R.string.sync_sentence_separator))

/** 同期の結果の文言。0 のものは省く。 */
fun RefreshResult.toSyncMessage(): UiText = sentences(
    UiText.Res(R.string.sync_fetched, programs, episodes),
    actionsText(),
    onHold.takeIf { it > 0 }?.let { UiText.Plural(R.plurals.sync_on_hold, it) },
)

/** 1 番組の同期の文言。 */
fun RefreshResult.toProgramSyncMessage(): UiText {
    if (onHold > 0) return UiText.Res(R.string.sync_program_gone)
    return sentences(
        UiText.Plural(R.plurals.sync_program_checked, episodes),
        actionsText() ?: UiText.Res(R.string.sync_program_up_to_date),
    )
}

/** 接続画面の文言。 */
fun ServerException.toUserMessage(): UiText = when (this) {
    is ServerException.Unauthorized -> UiText.Res(R.string.sync_error_unauthorized)
    is ServerException.Unreachable -> UiText.Res(R.string.sync_error_unreachable_connect)
    is ServerException.Failed -> UiText.Res(R.string.sync_error_server_failed, message.orEmpty())
}
