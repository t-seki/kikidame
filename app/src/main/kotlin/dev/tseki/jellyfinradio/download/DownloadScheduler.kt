package dev.tseki.jellyfinradio.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.tseki.jellyfinradio.domain.AppSettingsRepository
import dev.tseki.jellyfinradio.domain.DownloadRepository
import dev.tseki.jellyfinradio.domain.EpisodeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ダウンロードの操作をキュー（Room）と Worker の起動にまとめる入口。
 * Worker はユニーク（`KEEP`）なので、走っていればそのまま次の PENDING も拾う。
 */
@Singleton
class DownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: DownloadRepository,
    private val settings: AppSettingsRepository,
) {
    private val workManager get() = WorkManager.getInstance(context)

    suspend fun download(episodeId: EpisodeId) {
        downloads.enqueue(episodeId)
        kick()
    }

    suspend fun retry(episodeId: EpisodeId) {
        downloads.retry(episodeId)
        kick()
    }

    suspend fun cancel(episodeId: EpisodeId) = downloads.cancel(episodeId)

    /** PENDING が残っていれば Worker を起動する（アプリ起動時・設定変更時にも呼ぶ）。 */
    suspend fun kick() {
        val wifiOnly = settings.wifiOnly.first()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>().setConstraints(constraints).build()
        workManager.enqueueUniqueWork(DownloadWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** 「Wi-Fi のみ」を変えたら、待っている Worker の条件を差し替える。 */
    suspend fun reschedule() {
        workManager.cancelUniqueWork(DownloadWorker.UNIQUE_NAME)
        kick()
    }

    /** 進行中の各回とその割合。走っていなければ null。 */
    val progress: Flow<DownloadProgress?> =
        workManager.getWorkInfosForUniqueWorkFlow(DownloadWorker.UNIQUE_NAME).map { infos ->
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING } ?: return@map null
            val id = running.progress.getLong(DownloadWorker.KEY_EPISODE_ID, -1L)
            if (id < 0) null else DownloadProgress(EpisodeId(id), running.progress.getFloat(DownloadWorker.KEY_FRACTION, 0f))
        }

    /** Worker が制約待ち（Wi-Fi 待ちなど）で止まっているか。 */
    val isWaitingForConstraints: Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(DownloadWorker.UNIQUE_NAME).map { infos ->
            infos.any { it.state == WorkInfo.State.ENQUEUED }
        }
}

data class DownloadProgress(val episodeId: EpisodeId, val fraction: Float)
