package dev.tseki.jellyfinradio.download

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.tseki.jellyfinradio.data.download.DownloadOutcome
import dev.tseki.jellyfinradio.data.download.EpisodeDownloader
import dev.tseki.jellyfinradio.data.jellyfin.credentials
import dev.tseki.jellyfinradio.domain.DownloadQueue
import dev.tseki.jellyfinradio.domain.ServerException
import dev.tseki.jellyfinradio.domain.SessionRepository
import dev.tseki.jellyfinradio.domain.SessionState
import kotlinx.coroutines.flow.first

/**
 * `local_files` の PENDING を 1 本ずつ落とす（順序は `LocalFileDao.nextPending`: 手動が先、その中はキューに入れた順）。並列にはしない。
 * 1 本の失敗は行に記録して次へ進む。401 はログアウトして止まる。
 * 進捗は `setProgress` で各回 ID と割合を流す（一覧の進捗リング）。
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queue: DownloadQueue,
    private val downloader: EpisodeDownloader,
    private val sessionRepository: SessionRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val session = when (val s = sessionRepository.state.first()) {
            is SessionState.Ready -> s.session
            is SessionState.NeedsLibrary -> s.session
            is SessionState.SignedOut -> return Result.success()
        }
        val credentials = session.credentials()
        queue.resetRunning()
        queue.requeueFailed(MAX_ATTEMPTS)

        while (true) {
            val item = queue.nextPending() ?: break
            val episodeId = item.episode.id
            setProgress(workDataOf(KEY_EPISODE_ID to episodeId.value, KEY_FRACTION to 0f))
            val outcome = try {
                downloader.download(item, credentials) { downloaded, total ->
                    val fraction = if (total != null && total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                    setProgress(workDataOf(KEY_EPISODE_ID to episodeId.value, KEY_FRACTION to fraction))
                }
            } catch (e: ServerException.Unauthorized) {
                Log.w(TAG, "download unauthorized; signing out")
                sessionRepository.signOut()
                return Result.success()
            }
            when (outcome) {
                is DownloadOutcome.Failed -> Log.w(TAG, "download failed for episode ${episodeId.value}", outcome.cause)
                else -> Unit
            }
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "download-queue"
        const val KEY_EPISODE_ID = "episodeId"
        const val KEY_FRACTION = "fraction"
        const val MAX_ATTEMPTS = 3
        private const val TAG = "DownloadWorker"
    }
}
