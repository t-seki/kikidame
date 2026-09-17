package dev.tseki.jellyfinradio.sync

import android.util.Log
import dev.tseki.jellyfinradio.di.ApplicationScope
import dev.tseki.jellyfinradio.domain.AppSettingsRepository
import dev.tseki.jellyfinradio.domain.DownloadRepository
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.LibraryRefreshRepository
import dev.tseki.jellyfinradio.domain.ProgramId
import dev.tseki.jellyfinradio.domain.RefreshResult
import dev.tseki.jellyfinradio.domain.ServerException
import dev.tseki.jellyfinradio.domain.SessionRepository
import dev.tseki.jellyfinradio.domain.SessionState
import dev.tseki.jellyfinradio.download.DownloadKicker
import dev.tseki.jellyfinradio.playback.NowPlaying
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
    private val mutex = Mutex()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    // 連続した更新の結果を取りこぼさないよう少し余裕を持ち、溢れたら古い方を捨てる
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages

    /** 画面が消えても最後まで走らせたいとき（ライブラリ選択直後の初回取得）。 */
    fun launchRefresh() {
        applicationScope.launch { refresh() }
    }

    /** 全走査して同期する。[silent] なら文言を出さない（定期・起動時）。 */
    suspend fun refresh(silent: Boolean = false): RefreshResult? = guarded(silent) {
        refreshRepository.refresh(excluded = excluded()).also { say(silent, it.toSyncMessage()) }
    }

    /** 1 番組だけ取り込む。サーバ ID の無い番組は全走査（同期）にフォールバックする。 */
    suspend fun refreshProgram(programId: ProgramId): RefreshResult? = guarded(silent = false) {
        val result = refreshRepository.refreshProgram(programId)
        if (result != null) {
            say(false, "各回 ${result.episodes} を取得しました")
            result
        } else {
            refreshRepository.refresh(excluded = excluded()).also { say(false, it.toSyncMessage()) }
        }
    }

    /** 1 番組だけ同期する。サーバ ID の無い番組は何もしない（シート側でスイッチを無効にしている）。 */
    suspend fun syncProgram(programId: ProgramId): RefreshResult? = guarded(silent = false) {
        refreshRepository.syncProgram(programId, excluded = excluded())?.also { say(false, it.toProgramSyncMessage()) }
    }

    private fun excluded(): Set<EpisodeId> = setOfNotNull(nowPlaying.current.value)

    private fun say(silent: Boolean, message: String) {
        if (!silent) _messages.tryEmit(message)
    }

    private suspend fun guarded(silent: Boolean, block: suspend () -> RefreshResult?): RefreshResult? {
        if (!mutex.tryLock()) return null
        _isRefreshing.value = true
        try {
            if (sessionRepository.state.first() !is SessionState.Ready) return null
            // 手元のファイルが消えていないか先に整合する（#5）。ローカル I/O なのでネットワークの条件は見ない
            val reconciled = downloads.reconcileMissingFiles()
            if (reconciled > 0) say(silent, "手元に無くなっていた $reconciled 回の記録を整理しました")
            if (settings.wifiOnly.first() && network.isMetered()) {
                say(silent, "Wi-Fi に接続していないため更新しません")
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
            say(silent, "サーバの認証が切れました。もう一度ログインしてください")
            sessionRepository.signOut()
            return null
        } catch (e: ServerException.Unreachable) {
            say(silent, "サーバに接続できません。手元の一覧を表示しています")
            return null
        } catch (e: ServerException.Failed) {
            say(silent, "取得に失敗しました: ${e.message}")
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Room / DataStore / Keystore の失敗。落とさずに文言にする
            Log.e(TAG, "refresh failed", e)
            say(silent, "取り込みに失敗しました: ${e::class.simpleName}")
            return null
        } finally {
            _isRefreshing.value = false
            mutex.unlock()
        }
    }

    private companion object {
        const val TAG = "LibraryRefresher"
    }
}

/** 同期の結果の文言。0 のものは省く。 */
fun RefreshResult.toSyncMessage(): String = buildString {
    append("番組 $programs / 各回 $episodes を取得")
    val actions = listOfNotNull(
        enqueued.takeIf { it > 0 }?.let { "$it 回をダウンロード予約" },
        (deleted + removed).takeIf { it > 0 }?.let { "$it 回を削除" },
    )
    if (actions.isNotEmpty()) append("。").append(actions.joinToString("、"))
    if (onHold > 0) append("。$onHold 番組はサーバ上で見つからず、そのままにしました")
}

/** 1 番組の同期の文言。 */
fun RefreshResult.toProgramSyncMessage(): String {
    if (onHold > 0) return "この番組はサーバ上で見つかりません。何も変えていません"
    val actions = listOfNotNull(
        enqueued.takeIf { it > 0 }?.let { "$it 回をダウンロード予約" },
        (deleted + removed).takeIf { it > 0 }?.let { "$it 回を削除" },
    )
    return if (actions.isEmpty()) "各回 $episodes を確認。手元は最新です" else "各回 $episodes を確認。" + actions.joinToString("、")
}

/** 接続画面の文言。 */
fun ServerException.toUserMessage(): String = when (this) {
    is ServerException.Unauthorized -> "ユーザー名またはパスワードが違います"
    is ServerException.Unreachable -> "サーバに接続できません。URL とネットワークを確認してください"
    is ServerException.Failed -> "サーバがエラーを返しました: $message"
}
