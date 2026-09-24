package dev.tseki.kikidame.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.tseki.kikidame.domain.AppSettingsRepository
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.time.toJavaDuration

/**
 * 同期の起点を WorkManager に登録する。
 * - 定期: 6 時間ごと。起動のたびに `UPDATE` で登録し直し、「Wi-Fi のみ」の変更を拾う
 * - 起動時: 前回同期（または前回の試み。失敗を含む）から 1 時間以上なら一度だけ（`KEEP`）。制約を満たすまで待つ
 * 制約は `DownloadWorker` と同じ（Wi-Fi のみなら UNMETERED、そうでなければ CONNECTED）。充電中は課さない。
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettingsRepository,
    private val sessionRepository: SessionRepository,
    private val clock: Clock,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** アプリが前面に出たときに呼ぶ。定期の登録と、必要なら起動時の 1 回。 */
    suspend fun onAppStart() {
        schedulePeriodic()
        syncOnceIfStale()
    }

    suspend fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD.toJavaDuration())
            .setConstraints(constraints())
            .build()
        workManager.enqueueUniquePeriodicWork(SyncWorker.PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** 「Wi-Fi のみ」を変えたら制約を組み直す。 */
    suspend fun reschedule() = schedulePeriodic()
    /** 「別のサーバに接続」（#50）の前に、定期同期と起動時同期を止めて完了を待つ（消している最中に行を作り直さない）。 */
    suspend fun cancelAll() {
        workManager.cancelUniqueWork(SyncWorker.PERIODIC_NAME)
        workManager.cancelUniqueWork(SyncWorker.ONCE_NAME)
        for (name in listOf(SyncWorker.PERIODIC_NAME, SyncWorker.ONCE_NAME)) {
            workManager.getWorkInfosForUniqueWorkFlow(name).first { infos -> infos.all { it.state.isFinished } }
        }
    }

    private suspend fun syncOnceIfStale() {
        val ready = sessionRepository.state.first() as? SessionState.Ready ?: return
        if (!isStale(ready, clock.now())) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints()).build()
        workManager.enqueueUniqueWork(SyncWorker.ONCE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private suspend fun constraints(): Constraints {
        val wifiOnly = settings.wifiOnly.first()
        return Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
    }

    companion object {
        val PERIOD: Duration = 6.hours
        val STALE_AFTER: Duration = 1.hours

        /**
         * 起動時同期を積むか。前回同期（成功）と前回の試み（失敗を含む）の新しい方から [STALE_AFTER] たっていれば積む。
         * どちらも無ければ積む。失敗した直後に前面に出ても、到達できないサーバに接続し直さない（#135）。
         */
        internal fun isStale(ready: SessionState.Ready, now: Instant): Boolean {
            val last = listOfNotNull(ready.lastFetchedAt, ready.lastAttemptedAt).maxOrNull() ?: return true
            return now - last >= STALE_AFTER
        }
    }
}
