package dev.tseki.jellyfinradio.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.tseki.jellyfinradio.domain.AppSettingsRepository
import dev.tseki.jellyfinradio.domain.SessionRepository
import dev.tseki.jellyfinradio.domain.SessionState
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

/**
 * 同期の起点を WorkManager に登録する。
 * - 定期: 6 時間ごと。起動のたびに `UPDATE` で登録し直し、「Wi-Fi のみ」の変更を拾う
 * - 起動時: 前回同期から 1 時間以上なら一度だけ（`KEEP`）。制約を満たすまで待つ
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

    private suspend fun syncOnceIfStale() {
        val ready = sessionRepository.state.first() as? SessionState.Ready ?: return
        val last = ready.lastFetchedAt
        if (last != null && clock.now() - last < STALE_AFTER) return
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
    }
}
