package dev.tseki.kikidame.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.tseki.kikidame.domain.AppSettingsRepository
import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.domain.EpisodeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 「PENDING が積まれたので Worker を起こしてほしい」だけの窓口。同期（[dev.tseki.kikidame.sync.LibraryRefresher]）が使う。 */
fun interface DownloadKicker {
    suspend fun kick()
}
/**
 * ダウンロードの操作をキュー（Room）と Worker の起動にまとめる入口。
 * Worker はユニーク（`KEEP`）なので、走っていればそのまま次の PENDING も拾う。
 */
@Singleton
class DownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: DownloadRepository,
    private val settings: AppSettingsRepository,
) : DownloadKicker {
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

    /** PENDING が残っていれば Worker を起動する（アプリ起動時・設定変更時・同期の後にも呼ぶ）。 */
    override suspend fun kick() {
        val wifiOnly = settings.wifiOnly.first()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>().setConstraints(constraints).build()
        workManager.enqueueUniqueWork(DownloadWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * 「Wi-Fi のみ」を変えたら、条件待ちの Worker を新しい条件で組み直す。
     * 実行中の Worker は止めない（転送を切らない）。その Worker は今のキューを最後まで処理し、
     * 次に起動する Worker から新しい条件が効く。
     */
    suspend fun reschedule() {
        val infos = workManager.getWorkInfosForUniqueWorkFlow(DownloadWorker.UNIQUE_NAME).first()
        if (infos.any { it.state == WorkInfo.State.RUNNING }) return
        workManager.cancelUniqueWork(DownloadWorker.UNIQUE_NAME)
        kick()
    }

    /**
     * 「別のサーバに接続」（#50）の前に、キューの Worker を止めて完了を待つ。実行中の転送も切る
     * （行とファイルをこれから消すので、続けても `.part` が残るか、古いサーバへ無駄に取りに行くだけ）。
     */
    suspend fun cancelAll() {
        workManager.cancelUniqueWork(DownloadWorker.UNIQUE_NAME)
        workManager.getWorkInfosForUniqueWorkFlow(DownloadWorker.UNIQUE_NAME)
            .first { infos -> infos.all { it.state.isFinished } }
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
