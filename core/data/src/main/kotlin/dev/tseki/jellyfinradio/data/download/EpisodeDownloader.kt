package dev.tseki.jellyfinradio.data.download

import dev.tseki.jellyfinradio.data.jellyfin.JellyfinGateway
import dev.tseki.jellyfinradio.data.jellyfin.ServerCredentials
import dev.tseki.jellyfinradio.data.repository.RoomDownloadRepository
import dev.tseki.jellyfinradio.domain.DownloadQueue
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.EpisodeWithState
import dev.tseki.jellyfinradio.domain.ServerException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** 1 本のダウンロードの結果。 */
sealed interface DownloadOutcome {
    data class Done(val path: String, val sizeBytes: Long) : DownloadOutcome

    /** 途中でキャンセルされた（行は既に無い）。 */
    data object Cancelled : DownloadOutcome

    data class Failed(val cause: Exception) : DownloadOutcome
}

/**
 * 1 本を `.part` に書いて完了でリネームする。既存の `.part` があれば `Range` で続きを要求し、
 * サーバが無視して全体を返したら（200）書き直す。チャンクごとにキャンセルを見る。
 * 状態の更新（RUNNING / DONE / FAILED）はここで [DownloadQueue] に対して行う。
 */
@Singleton
class EpisodeDownloader @Inject constructor(
    private val gateway: JellyfinGateway,
    private val queue: DownloadQueue,
) {
    suspend fun download(
        item: EpisodeWithState,
        credentials: ServerCredentials,
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        val episodeId = item.episode.id
        // 起こらないはずだが、PENDING のまま残すと Worker が同じ行を拾い続けるので FAILED に落とす
        val serverId = item.episode.serverItemId ?: return invalid(episodeId, "no server id")
        val targetPath = item.localFile?.path ?: return invalid(episodeId, "no target path")
        val target = File(targetPath)
        val part = File(targetPath + RoomDownloadRepository.PART_SUFFIX)

        queue.markRunning(episodeId)
        return try {
            withContext(Dispatchers.IO) {
                target.parentFile?.mkdirs()
                val existing = if (part.isFile) part.length() else 0L
                gateway.openDownload(credentials, serverId, rangeStart = existing).use { stream ->
                    val resumedFrom = stream.resumedFrom
                    val append = when (resumedFrom) {
                        null -> false // 200: 全体が来るので書き直す
                        existing -> true // 206: 続きから
                        else -> throw IOException("server resumed from $resumedFrom, expected $existing")
                    }
                    var written = if (append) existing else 0L
                    FileOutputStream(part, append).use { out ->
                        val buffer = ByteArray(CHUNK)
                        var sinceCheck = 0L
                        while (true) {
                            val n = stream.body.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            written += n
                            sinceCheck += n
                            if (sinceCheck >= CHECK_EVERY) {
                                sinceCheck = 0
                                if (!queue.isStillWanted(episodeId)) return@withContext cancelled(part)
                                onProgress(written, stream.totalBytes)
                            }
                        }
                    }
                    if (!queue.isStillWanted(episodeId)) return@withContext cancelled(part)
                    val total = stream.totalBytes
                    if (total != null && written != total) {
                        throw IOException("incomplete: $written of $total bytes")
                    }
                    if (!part.renameTo(target)) throw IOException("rename failed: ${part.name}")
                    queue.markDone(episodeId, target.absolutePath, target.length())
                    onProgress(written, total ?: written)
                    DownloadOutcome.Done(target.absolutePath, target.length())
                }
            }
        } catch (e: ServerException.Unauthorized) {
            // 認証切れは呼び出し側（Worker）がログアウトへ導く。行は PENDING に戻して次回に備える
            queue.resetToPending(episodeId)
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (queue.isStillWanted(episodeId)) queue.markFailed(episodeId)
            DownloadOutcome.Failed(e)
        }
    }

    private suspend fun invalid(episodeId: EpisodeId, reason: String): DownloadOutcome {
        queue.markFailed(episodeId)
        return DownloadOutcome.Failed(IllegalStateException(reason))
    }
    private fun cancelled(part: File): DownloadOutcome {
        part.delete()
        return DownloadOutcome.Cancelled
    }

    companion object {
        const val CHUNK = 64 * 1024
        const val CHECK_EVERY = 1024L * 1024
    }
}
