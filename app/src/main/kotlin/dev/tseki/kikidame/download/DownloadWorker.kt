package dev.tseki.kikidame.download

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.tseki.kikidame.data.download.DownloadOutcome
import dev.tseki.kikidame.data.download.EpisodeDownloader
import dev.tseki.kikidame.domain.DownloadQueue
import dev.tseki.kikidame.domain.SourceException
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.sync.SyncFailureNotifier
import kotlinx.coroutines.flow.first

/**
 * `local_files` の待ち行列を 1 本ずつ落とす（順序は `LocalFileDao.nextPending`: 手動が先、その中はキューに入れた順）。並列にはしない。
 * 待ち行列に入るのは、`state` が PENDING の行と、差し替え（ADR 0014、#371）を予約した行（`state` は DONE のまま、`replaceState` が PENDING）。
 * 1 本の失敗は行に記録して（FAILED にし、試行回数を数えて）次へ進む。差し替えの失敗は行に記録せず、差し替えの印（`replaceState`）を
 * 下ろすだけ（試行回数も数えず、今のファイルと版は残る。次の同期でまた予約される）。
 * 401 はログアウトして止まる（同期の失敗の通知も消す。#346）。
 * 進捗は `setProgress` で各回 ID と割合を流す（一覧の進捗リング。リングは `state` が RUNNING の行にだけ出るので、差し替えの間は出ない）。
 * 差し替えは、落とし終えた時点で聴いている回（[NowPlaying.excludedFromSync]）なら差し替えない。
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queue: DownloadQueue,
    private val downloader: EpisodeDownloader,
    private val sessionRepository: SessionRepository,
    private val syncFailureNotifier: SyncFailureNotifier,
    private val nowPlaying: NowPlaying,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 接続していなければ何もしない。取得元への接続の情報は取得元の実装が持つ（ADR 0010）
        if (sessionRepository.state.first() is SessionState.SignedOut) return Result.success()
        queue.resetRunning()
        queue.resetRunningReplacements()
        queue.requeueFailed(MAX_ATTEMPTS)

        while (true) {
            val item = queue.nextPending() ?: break
            val episodeId = item.episode.id
            setProgress(workDataOf(KEY_EPISODE_ID to episodeId.value, KEY_FRACTION to 0f))
            val outcome = try {
                downloader.download(item, isNowPlaying = { it == nowPlaying.excludedFromSync }) { downloaded, total ->
                    val fraction = if (total != null && total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                    setProgress(workDataOf(KEY_EPISODE_ID to episodeId.value, KEY_FRACTION to fraction))
                }
            } catch (e: SourceException.Unauthorized) {
                Log.w(TAG, "download unauthorized; signing out")
                // ログアウトでは同期の失敗の通知も消す（記録はセッションと一緒に消える。#346 の決定 6）
                syncFailureNotifier.cancel()
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
