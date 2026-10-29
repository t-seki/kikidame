package dev.tseki.kikidame.sync

import android.util.Log
import dev.tseki.kikidame.di.ApplicationScope
import dev.tseki.kikidame.domain.AppSettingsRepository
import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.LibraryRefreshRepository
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.RefreshResult
import dev.tseki.kikidame.domain.ScanProgress
import dev.tseki.kikidame.domain.SourceException
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.domain.SyncAttempt
import dev.tseki.kikidame.domain.SyncOutcome
import dev.tseki.kikidame.domain.isDeviceFolder
import dev.tseki.kikidame.domain.wifiOnlyApplies
import dev.tseki.kikidame.download.DownloadKicker
import dev.tseki.kikidame.R
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * 更新と同期の唯一の入口。同時に 1 つしか走らせず、結果を文言にして画面へ流す。
 *
 * - [refresh]: 全走査 = ライブラリ全体の同期（ADR 0003）。番組一覧の「引っ張って更新」、定期・起動時の Worker が呼ぶ
 * - [syncProgram]: 1 番組の同期。各回一覧の「引っ張って更新」（[launchSyncProgram]。#279）と、同期のシートを閉じたとき
 *   （[launchSyncProgramAfterRunning]。#311）が呼ぶ
 * - 画面の操作から来る同期（番組一覧・各回一覧の引っ張って更新、シートを閉じたときの同期）は `applicationScope` で走らせ、
 *   画面を離れても最後まで続ける（#311）
 * - シートを閉じたときの同期は、走っている同期（手動・裏のどちらでも）に合流も捨てられもせず、終わるのを待ってから走る。
 *   待っている間に [cancelAndAwait] で取り消されたら走らせない（#311）
 * - 「Wi-Fi のみ」は手動を含めて効く。従量制なら取得元に触らず理由を出して終える。手元のファイルの整合（#5）はその前に走る。
 *   取得元が端末のフォルダなら効かせない（#199 の決定 4。[dev.tseki.kikidame.domain.wifiOnlyApplies]）
 * - 401 はログアウトと同じ処理をする（セッション状態が変わり、画面側が接続画面へ導く）。端末のフォルダでアクセスが切れたときも同じで、
 *   文言は「フォルダを選び直して」にする（#199 の決定 5）
 * - 定期・起動時の silent な同期ではクルクルを出さない。その最中に手動の操作が来たら、走っている同期に合流する（#134）
 * - 結果の文言は手元の変化（新しい回・予約・削除）だけを並べる。手動は変化が無ければ「最新の状態です」、silent は変化があったときだけ出す（#142）
 * - silent な同期の間は [isSyncingInBackground] を立てる（画面は細いバーを出す。#138）。合流したらクルクルに切り替える
 * - 全走査でタグを読む回がある間は、読んだ数を [tagProgress] に流す（silent でも手動でも。#209）
 * - 全走査が途中で失敗しても、それまでに番組ごとの同期が予約していればダウンロードの Worker を起こす。取り消し・認証の失敗では起こさない（#209）
 * - 再生中の回（削除と差し替えの予約から外す回。ADR 0011）は、全走査の中で番組ごとに読み直す（#209）
 * - 全走査の最後の試みの結果（成功・失敗とその理由・「Wi-Fi ではない」の見送り）と時刻を記録する（手動でも裏でも。#346 の決定 3）。
 *   1 番組の同期、401（ログアウトする）、取り消しは記録しない。全走査が成功したら、同期の失敗の通知（[SyncFailureNotifier]）を消す
 * - ログアウトと取得元を変える前（[cancelAndAwait]）と 401 でも、同期の失敗の通知を消す（記録はログアウト・取得元を変えるときにセッションと一緒に消える。#346 の決定 6）
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
    private val notifier: SyncFailureNotifier,
    private val clock: Clock,
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

    private val _tagProgress = MutableStateFlow<ScanProgress?>(null)

    /**
     * 全走査でタグを読んでいる回の数と、読み終えた数（#209 の決定 6）。タグを読む回が無い間（全走査でないとき、
     * 2 回目以降で変化の無い同期）は null。実行が終われば（成功・失敗・取り消しのどれでも）null に戻る。
     */
    val tagProgress: StateFlow<ScanProgress?> = _tagProgress

    // 連続した更新の結果を取りこぼさないよう少し余裕を持ち、溢れたら古い方を捨てる
    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<UiText> = _messages

    /**
     * [cancelAndAwait] を呼んだ回数。[lock] の中で読み書きする。[launchSyncProgramAfterRunning] が待っている間に取り消されたかを、
     * 待つ前と後で見比べて知る（待っている呼び出しは取り消される実行の job に入っていないため。#311）。
     */
    private var cancellations = 0L

    /** 画面が消えても最後まで走らせたいとき（ライブラリ選択直後の初回取得、番組一覧の引っ張って更新。#311）。 */
    fun launchRefresh() {
        applicationScope.launch { refresh() }
    }

    /** 各回一覧の「引っ張って更新」。[syncProgram] を `applicationScope` で走らせ、画面を離れても最後まで続ける（#311 の決定 9）。 */
    fun launchSyncProgram(programId: ProgramId, orFullSync: Boolean) {
        applicationScope.launch { syncProgram(programId, orFullSync) }
    }

    /**
     * 同期のシートを閉じたときの 1 番組の同期（#311）。`applicationScope` で走らせ、画面を離れても最後まで続ける。
     * 走っている同期があれば、合流せず（裏の全走査がこの番組を処理し終えた後に変えた設定を反映するため）捨てられもせず、
     * 終わるのを待ってから走る。[shouldSync] は走らせる直前（待った後）に呼び、false なら走らせない
     * （設定が開いたときと同じなら同期しない、の判定に使う）。待っている間に [cancelAndAwait] が呼ばれたら走らせない。
     * 番組の行が無いか取得元 ID が無ければ何もしない（全走査には切り替えない）。
     */
    fun launchSyncProgramAfterRunning(programId: ProgramId, shouldSync: suspend () -> Boolean = { true }) {
        applicationScope.launch {
            awaitTurn(shouldSync) { syncOne(programId) }
        }
    }

    /**
     * 全走査して同期する。[silent] ならクルクルを出さず（定期・起動時）、代わりに [isSyncingInBackground] を立てて細いバーを出す（#138）。
     * silent でも手元に変化があれば結果の文言を出す（#142）。変化が無い結果と失敗は出さない（スナックバーの話。失敗も最後の試みとして記録し、
     * 設定の「最終同期」の行に出る。裏の同期の失敗が続いたときの通知は [refreshInBackground]。#346）。
     * ただし手動の操作が合流したら、そこからバーを消してクルクルを出し、結果の文言（変化が無ければ「最新の状態です」）を出す。
     */
    suspend fun refresh(silent: Boolean = false): RefreshResult? = guarded(silent, scansAll = true) { sync() }

    /**
     * 裏の同期（[SyncWorker]）の全走査。中身は silent な [refresh] と同じで、加えて、この実行が失敗で終わり、最後まで手動の操作が
     * 合流しなかったとき（手動が合流したら結果の文言がスナックバーに出るので、手動として扱う。#346 の決定 5）の失敗を返す。
     * ほかの実行が走っていて何もしなかったときと、見送り・成功では [BackgroundRefresh.failure] は null。
     */
    suspend fun refreshInBackground(): BackgroundRefresh {
        var started: Run? = null
        val result = guarded(silent = true, scansAll = true, onStart = { started = it }) { sync() }
        val failure = started?.let { run -> synchronized(lock) { run.failure.takeIf { run.silent } } }
        return BackgroundRefresh(result, failure)
    }

    /**
     * 1 番組だけ同期する。番組の行が無いか取得元 ID の無い番組は、[orFullSync] なら全走査（同期）に切り替え（各回一覧の
     * 「引っ張って更新」。#279）、そうでなければ何もしない（シート側でスイッチを無効にしている）。
     */
    suspend fun syncProgram(programId: ProgramId, orFullSync: Boolean = false): RefreshResult? = guarded(silent = false, scansAll = false) {
        syncOne(programId) ?: if (orFullSync) sync() else null
    }

    /** 1 番組を同期して結果の文言を出す。番組の行が無いか取得元 ID が無ければ null（文言は出さない）。 */
    private suspend fun Run.syncOne(programId: ProgramId): RefreshResult? =
        refreshRepository.syncProgram(programId, excluded = excluded())?.also { report(it.toProgramSyncMessage()) }

    /**
     * 全走査して結果の文言を出す。手元に変化があれば silent でも出す（#142）。成功を最後の試みとして記録し、同期の失敗の通知を消す（#346）。
     * 各回一覧の引っ張って更新が全走査に切り替えたとき（#279）も全走査なので、ここから先の失敗は記録する。
     */
    private suspend fun Run.sync(): RefreshResult {
        scansAll = true
        return refreshRepository.refresh(
            excluded = { excluded() },
            onProgress = { _tagProgress.value = it },
            onEnqueued = { enqueuedSoFar.addAndGet(it) },
        ).also {
            report(it.toSyncMessage(), evenIfSilent = it.hasChanges)
            record(SyncOutcome.Succeeded)
        }
    }

    /** 聴いている回は削除と差し替えの予約（ADR 0011）から外す。ただし聴き終えて止まっている回は「再生済みなら削除」に任せる（#27）。 */
    private fun excluded(): Set<EpisodeId> = setOfNotNull(nowPlaying.excludedFromSync)

    /**
     * 1 回の実行。手動の操作が合流すると [silent] が外れ、まだ出していない結果の文言があればそこで出る。
     * 手元に変化があった結果は silent でも合流を待たずに出しているので、合流しても二度は出ない（#142）。
     */
    private inner class Run(silent: Boolean, scansAll: Boolean) {
        /** [silent] と [unreported] と [failure] は [lock] の中で読み書きする。 */
        var silent: Boolean = silent

        /** 全走査の実行か（最後の試みとして記録するか。#346）。1 番組の同期が全走査に切り替えたら立てる。 */
        var scansAll: Boolean = scansAll

        /** この実行が全走査の失敗で終わったときの失敗（#346）。[refreshInBackground] が通知を出すかを決めるのに使う。 */
        var failure: SyncOutcome.Failure? = null

        /** silent なうちに出しそびれた結果の文言。合流した時点で出す。 */
        var unreported: UiText? = null

        /** 合流した呼び出しが待つ、この実行の結果。 */
        val result = CompletableDeferred<RefreshResult?>()

        /** この実行を走らせている呼び出し側のコルーチンの Job。[cancelAndAwait] が取り消す。 */
        var job: Job? = null

        /** 全走査の途中で、番組ごとの同期が予約したダウンロードの数（#209）。走査が途中で失敗しても Worker を起こすために数える。 */
        val enqueuedSoFar = AtomicInteger()

        /** 途中の文言（手元の整合）。silent なら捨てる。 */
        fun say(message: UiText) {
            synchronized(lock) {
                if (!silent) _messages.tryEmit(message)
            }
        }

        /**
         * 結果の文言（成功・エラー・Wi-Fi のみ）。1 回の実行で 1 回だけ呼ぶ。
         * silent なら取っておき、この後の片付けまでに手動が合流したらそこで出す。
         * [evenIfSilent]（手元に変化があった結果。#142）なら silent でもすぐ出し、取っておかない（合流しても二度は出ない）。
         */
        fun report(message: UiText, evenIfSilent: Boolean = false) {
            synchronized(lock) {
                if (silent && !evenIfSilent) unreported = message else _messages.tryEmit(message)
            }
        }

        /**
         * 全走査の最後の試みとして [outcome] を記録する（#346 の決定 3）。全走査でない実行（1 番組の同期）では何もしない。
         * 記録に失敗しても同期の結果は変えない（ログに残す）。成功なら同期の失敗の通知を消す（出したことの記録は記録と一緒に消える）。
         */
        suspend fun record(outcome: SyncOutcome) {
            if (!scansAll) return
            if (outcome is SyncOutcome.Failure) synchronized(lock) { failure = outcome }
            try {
                sessionRepository.recordSyncAttempt(SyncAttempt(clock.now(), outcome))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not record the sync attempt", e)
            }
            if (outcome == SyncOutcome.Succeeded) notifier.cancel()
        }

        /** 失敗の文言（スナックバー）を出し、全走査なら最後の試みとして記録する。 */
        suspend fun fail(outcome: SyncOutcome.Failure) {
            report(outcome.reason())
            record(outcome)
        }
    }

    /**
     * 走っている実行（定期・手動・初回のどれでも）を取り消し、終わるのを待つ。「取得元を変える」とログアウトの前に呼ぶ
     * （取得元を変えた後に古い取得元の走査が走り続けたり、結果が新しい取得元の DB に書かれたり、「同時に 1 つ」で新しい取得元の同期が
     * 捨てられたりしないように。#198）。実行が無ければ何もしない。走査はフォルダごと・ファイルごとに取り消しを見るので、待ちは短い。
     * 取り消しが終わってから、同期の失敗の通知を消す（ログアウト・取得元を変えるときに消す。#346 の決定 6。先に消すと、待っている間に
     * 終わった実行の後に通知が残りうるので、#198 と同じく取り消しを待ってから切り替える）。
     */
    suspend fun cancelAndAwait() {
        val run = synchronized(lock) {
            cancellations++
            running
        }
        if (run != null) {
            run.job?.cancel(CancellationException("source changed"))
            run.result.await()
        }
        notifier.cancel()
    }

    /**
     * 同時に 1 つしか走らせない。走っている間に来た呼び出しは:
     * - silent な実行の最中の手動 → 合流する。クルクルを出し、まだ出していない結果の文言があれば出させ（変化があって既に出した文言は出し直さない。#142）、
     *   その実行の結果を待って返す（全走査をやり直さない）
     * - それ以外 → 何もせず null
     *
     * シートを閉じたときの同期は、合流も捨てられもせずに待つ別の経路（[awaitTurn]）を通る（#311）。
     *
     * [scansAll] は新しく始める実行が全走査か（最後の試みとして記録するか。#346）。[refresh] は true、[syncProgram] は false
     * （全走査に切り替えたら [sync] の中で立つ）。合流したときと何もしなかったときは使わない。
     * [onStart] は新しく実行を始めたときだけ、その実行を渡して [lock] の中で呼ぶ（[refreshInBackground] が、最後まで手動が合流しなかったかと
     * 失敗を後から読むため）。合流したときと何もしなかったときは呼ばない。
     */
    private suspend fun guarded(
        silent: Boolean,
        scansAll: Boolean,
        onStart: (Run) -> Unit = {},
        block: suspend Run.() -> RefreshResult?,
    ): RefreshResult? {
        var joined: Run? = null
        val callerJob = currentCoroutineContext()[Job]
        val run = synchronized(lock) {
            val current = running
            when {
                current == null -> start(silent, scansAll, callerJob).also(onStart)
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
        return runToEnd(run, block)
    }

    /**
     * 走っている実行が終わるのを待ってから、手動の実行として [block] を走らせる（シートを閉じたときの同期。#311）。
     * silent な実行にも合流しない。終わった直後に別の呼び出しに先を越されたら、それも待つ。
     * 呼んでから走らせるまでの間に [cancelAndAwait] が呼ばれたら、走らせずに null を返す。
     * [shouldSync] は走らせる直前に呼び、false なら走らせずに null を返す。
     */
    private suspend fun awaitTurn(shouldSync: suspend () -> Boolean, block: suspend Run.() -> RefreshResult?): RefreshResult? {
        val callerJob = currentCoroutineContext()[Job]
        val cancellationsAtStart = synchronized(lock) { cancellations }
        while (true) {
            val current = synchronized(lock) {
                if (cancellations != cancellationsAtStart) return null
                running
            }
            if (current != null) {
                current.result.await()
                continue
            }
            if (!askShouldSync(shouldSync)) return null
            val run = synchronized(lock) {
                if (cancellations != cancellationsAtStart) return null
                if (running == null) start(silent = false, scansAll = false, callerJob) else null
            } ?: continue
            return runToEnd(run, block)
        }
    }

    /**
     * [awaitTurn] の走らせる直前の判定。判定は Room を読む（設定を比べる）ので、失敗しても落とさず（`applicationScope` には
     * 例外を受け取る先が無い）、[execute] と同じく取り込み失敗の文言にして、走らせない。
     */
    private suspend fun askShouldSync(shouldSync: suspend () -> Boolean): Boolean =
        try {
            shouldSync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "could not decide whether to sync", e)
            _messages.tryEmit(UiText.Res(R.string.sync_error_import_failed, e::class.simpleName.orEmpty()))
            false
        }

    /**
     * 新しい実行を [running] に入れ、表示を立てる。[lock] の中で呼ぶ。
     * Run を running に入れるのと同じ synchronized の中で job を入れる（cancelAndAwait が job の無い Run を見る隙間を作らない）。
     */
    private fun start(silent: Boolean, scansAll: Boolean, callerJob: Job?): Run = Run(silent, scansAll).also {
        it.job = callerJob
        running = it
        if (silent) _isSyncingInBackground.value = true else _isRefreshing.value = true
    }

    /** [start] した実行を走らせ、終わったら（成功・失敗・取り消しのどれでも）片付けて、待っている呼び出しに結果を渡す。 */
    private suspend fun runToEnd(run: Run, block: suspend Run.() -> RefreshResult?): RefreshResult? {
        var result: RefreshResult? = null
        try {
            result = run.execute(block)
            return result
        } finally {
            synchronized(lock) {
                running = null
                _isRefreshing.value = false
                _isSyncingInBackground.value = false
                _tagProgress.value = null
            }
            run.result.complete(result)
        }
    }

    private suspend fun Run.execute(block: suspend Run.() -> RefreshResult?): RefreshResult? {
        try {
            val state = sessionRepository.state.first() as? SessionState.Ready ?: return null
            // 手元のファイルが消えていないか先に整合する（#5）。ローカル I/O なのでネットワークの条件は見ない
            val reconciled = downloads.reconcileMissingFiles()
            if (reconciled > 0) say(UiText.Plural(R.plurals.sync_reconciled, reconciled))
            if (wifiOnlyApplies(settings.wifiOnly.first(), state) && network.isMetered()) {
                report(UiText.Res(R.string.sync_not_on_wifi))
                // 見送りも最後の試みとして記録する。取得元に問い合わせていないので lastAttemptedAt は更新しない（#346 の決定 3）
                record(SyncOutcome.SkippedNotOnWifi)
                return null
            }
            val result = block() ?: return null
            // 同期は済んでいる。Worker を起こせなくても結果は返す（次の起動やダウンロード操作で拾われる）
            if (result.enqueued > 0) kickDownloads()
            return result
        } catch (e: SourceException.Unauthorized) {
            // ログアウトすると画面が接続画面へ移るので、文言を先に出す。端末のフォルダはアクセスが切れた（#199 の決定 5）
            val deviceFolder = sessionRepository.state.first().isDeviceFolder
            report(UiText.Res(if (deviceFolder) R.string.sync_error_device_folder_lost else R.string.sync_error_session_expired))
            // 失敗としては記録しない。最後の試みの記録はログアウトで消え、同期の失敗の通知もここで消す（#346 の決定 2・6）
            notifier.cancel()
            sessionRepository.signOut()
            return null
        } catch (e: SourceException.Unreachable) {
            kickIfEnqueuedBeforeFailure()
            fail(SyncOutcome.Unreachable)
            return null
        } catch (e: SourceException.Failed) {
            kickIfEnqueuedBeforeFailure()
            fail(SyncOutcome.FetchFailed(e.message.orEmpty()))
            return null
        } catch (e: CancellationException) {
            // 取り消し（取得元を変える・ログアウトの前の cancelAndAwait）では起こさない。取得元を変える処理（SourceChanger）は
            // ダウンロードの Worker を止めてから走査を取り消し、手元を消すので、ここで起こすと消す前に Worker が動き出す
            throw e
        } catch (e: Exception) {
            // Room / DataStore / Keystore の失敗。落とさずに文言にする
            Log.e(TAG, "refresh failed", e)
            kickIfEnqueuedBeforeFailure()
            fail(SyncOutcome.ImportFailed(e::class.simpleName.orEmpty()))
            return null
        }
    }

    /**
     * 全走査が途中で失敗しても、それまでに番組ごとの同期が予約していれば Worker を起こす（#209）。予約は DB に残っていて、
     * 次の同期ではその回の予約が数に入らない（もう行がある）ので、ここで起こさないと次の起動かダウンロードの操作まで待つ。
     * 認証の失敗（ログアウトする）と取り消しでは起こさない。ブロッキングの I/O の最中に取り消されると、取り消しが
     * [SourceException.Unreachable] などに包まれて届くことがあるので、先に取り消し済みかを確かめ、そうなら取り消しとして投げ直す。
     */
    private suspend fun Run.kickIfEnqueuedBeforeFailure() {
        currentCoroutineContext().ensureActive()
        if (enqueuedSoFar.get() > 0) kickDownloads()
    }

    /** ダウンロードの Worker を起こす。起こせなくても同期の結果は変えない（次の起動やダウンロード操作で拾われる）。 */
    private suspend fun kickDownloads() {
        try {
            kicker.kick()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not start the download worker", e)
        }
    }

    private companion object {
        const val TAG = "LibraryRefresher"
    }
}

/**
 * 裏の同期（[LibraryRefresher.refreshInBackground]）の結果。[result] は [LibraryRefresher.refresh] と同じ。[failure] は、この実行が全走査の
 * 失敗で終わり、最後まで手動の操作が合流しなかったときの失敗（#346 の決定 5）。
 */
data class BackgroundRefresh(val result: RefreshResult?, val failure: SyncOutcome.Failure?)
