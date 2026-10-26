package dev.tseki.kikidame.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.domain.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * 定期・起動時の同期。中身は [LibraryRefresher.refreshInBackground]（silent な [LibraryRefresher.refresh] と同じ。手動と同じ入口。silent なのでクルクルを出さず、結果の文言は手元に変化があったときだけ出す（#142）。
 * 手動の操作が合流したら、変化が無くても結果を出す（#134））。文言が出るのは `messages` を collect している番組一覧・各回一覧を開いているときだけで、それ以外の画面にいる間や閉じている間の結果は後から出さない。
 * ただし失敗は例外で、全走査の最後の試みとして記録して設定の「最終同期」の行に出し、裏の同期の失敗が 24 時間（[NOTIFY_AFTER]）続いたら通知を出す（[shouldNotifyFailure]。#346）。
 * 走っている間は番組一覧・各回一覧のトップバーの下に細いバーと「バックグラウンドで同期中…」が出て、手動の操作が合流したらバーは消えてクルクルに切り替わる（#138）。
 * 失敗しても `retry` にはせず次の周期を待つ。401 は Refresher がログアウトする。
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val refresher: LibraryRefresher,
    private val sessionRepository: SessionRepository,
    private val notifier: SyncFailureNotifier,
    private val clock: Clock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val before = sessionRepository.state.first()
        // 出したことの記録が無いのに通知が残っていたら消す（ダウンロードの 401 でログアウトしたときなど、記録だけが消えた後。#346）
        if ((before as? SessionState.Ready)?.syncFailureNotified != true) notifier.cancel()
        val (result, failure) = refresher.refreshInBackground()
        if (result == null) {
            Log.i(TAG, "sync skipped or failed")
        } else {
            // 画面の文言は変化だけなので、ログには取得した件数も出す
            Log.i(
                TAG,
                "sync: programs=${result.programs} episodes=${result.episodes} new=${result.newEpisodes} " +
                    "enqueued=${result.enqueued} deleted=${result.deleted + result.removed} onHold=${result.onHold}",
            )
        }
        if (failure != null) notifyIfLong(failure)
        return Result.success()
    }

    private suspend fun notifyIfLong(failure: SyncOutcome.Failure) {
        try {
            notifyFailureIfLong(failure, sessionRepository, notifier, clock.now())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not notify the sync failure", e)
        }
    }

    companion object {
        const val PERIODIC_NAME = "sync-periodic"
        const val ONCE_NAME = "sync-once"
        private const val TAG = "SyncWorker"

        /** 裏の同期の失敗を通知するまでの、最終同期（最後に全走査が成功した時刻）からの時間（#346 の決定 7）。 */
        val NOTIFY_AFTER: Duration = 24.hours

        /**
         * 裏の同期（最後まで手動が合流しなかった全走査）が失敗で終わったときに、同期の失敗の通知を出すか（#346 の決定 5）。
         * 最終同期（[lastFetchedAt]）から [NOTIFY_AFTER] 以上たっていて、まだ出していない（[notified] でない）ときだけ出す。
         * 一度も成功していない（[lastFetchedAt] が null）ときは出さない（接続した直後の取り込みは画面を開いたまま走り、失敗がその場でスナックバーに出るため）。
         * 出したら、次に全走査が成功するまで（利用者が通知を払っても）出し直さない。
         */
        /**
         * 裏の同期の失敗が [NOTIFY_AFTER] 続いていて、まだ出していなければ通知を出し、実際に出せたときだけ出したことを記録する
         * （#346 の決定 5。通知の許可が無くて出せなかったときは記録せず、後で許可されたら次の失敗で出る）。出したら true。
         */
        internal suspend fun notifyFailureIfLong(
            failure: SyncOutcome.Failure,
            sessionRepository: SessionRepository,
            notifier: SyncFailureNotifier,
            now: Instant,
        ): Boolean {
            val ready = sessionRepository.state.first() as? SessionState.Ready ?: return false
            if (!shouldNotifyFailure(ready.lastFetchedAt, ready.syncFailureNotified, now)) return false
            if (!notifier.show(failure.reason())) return false
            sessionRepository.markSyncFailureNotified()
            return true
        }

        internal fun shouldNotifyFailure(lastFetchedAt: Instant?, notified: Boolean, now: Instant): Boolean {
            if (notified || lastFetchedAt == null) return false
            return now - lastFetchedAt >= NOTIFY_AFTER
        }
    }
}
