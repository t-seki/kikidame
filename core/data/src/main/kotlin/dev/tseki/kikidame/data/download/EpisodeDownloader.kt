package dev.tseki.kikidame.data.download

import dev.tseki.kikidame.data.repository.RoomDownloadRepository
import dev.tseki.kikidame.data.source.SourceGateway
import dev.tseki.kikidame.domain.DownloadQueue
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.SourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/** 1 本のダウンロードの結果。 */
sealed interface DownloadOutcome {
    data class Done(val path: String, val sizeBytes: Long) : DownloadOutcome

    /** 途中でキャンセルされた（行は既に無い）。 */
    data object Cancelled : DownloadOutcome

    data class Failed(val cause: Exception) : DownloadOutcome

    /**
     * 差し替え（ADR 0011）を落とし終えた時点で、その回が聴いている回だった。差し替えずに書きかけを捨てた（今のファイルのまま）。
     * 次の同期でまた予約される。
     */
    data object Postponed : DownloadOutcome
}

/**
 * 1 本を `.part` に書いて完了でリネームする。既存の `.part` があれば続きを要求し、
 * 取得元が応じずに全体を返したら書き直す。取得元には [SourceGateway] だけを通して触る（ADR 0007）。約 1 MB（[CHECK_EVERY]）ごとにキャンセルを見る。
 * 状態の更新（RUNNING / DONE / FAILED）はここで [DownloadQueue] に対して行う。
 *
 * 差し替え（[dev.tseki.kikidame.domain.LocalFile.replacing]、ADR 0011）は [replace] で落とす。行は DONE のまま（今のファイルで聴ける）で、
 * いつも頭から落とし（`.part` の続きを要求しない）、落とし終えたら今のファイルの場所へ移して差し替える。
 * 落とし終えた時点でその回が聴いている回なら、差し替えずに `.part` を捨てる（[DownloadOutcome.Postponed]）。
 * 失敗したら `.part` を捨てて差し替えをやめる（今のファイルと落としたときの版は残る）。
 */
@Singleton
class EpisodeDownloader @Inject constructor(
    private val source: SourceGateway,
    private val queue: DownloadQueue,
) {
    /** [isNowPlaying] は、その回が聴いている回か（差し替えを落とし終えた時点で見る）。 */
    suspend fun download(
        item: EpisodeWithState,
        isNowPlaying: (EpisodeId) -> Boolean = { false },
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        if (item.localFile?.replacing == true) return replace(item, isNowPlaying, onProgress)
        val episodeId = item.episode.id
        // 起こらないはずだが、PENDING のまま残すと Worker が同じ行を拾い続けるので FAILED に落とす
        val sourceId = item.episode.sourceItemId ?: return invalid(episodeId, "no source id")
        val targetPath = item.localFile?.path ?: return invalid(episodeId, "no target path")
        val target = File(targetPath)
        val part = File(targetPath + RoomDownloadRepository.PART_SUFFIX)

        queue.markRunning(episodeId)
        return try {
            withContext(Dispatchers.IO) {
                target.parentFile?.mkdirs()
                val existing = if (part.isFile) part.length() else 0L
                source.openDownload(sourceId, rangeStart = existing).use { stream ->
                    val resumedFrom = stream.resumedFrom
                    val append = when (resumedFrom) {
                        null -> false // 200: 全体が来るので書き直す
                        existing -> true // 206: 続きから
                        else -> throw IOException("source resumed from $resumedFrom, expected $existing")
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
        } catch (e: SourceException.Unauthorized) {
            // 認証切れは呼び出し側（Worker）がログアウトへ導く。行は PENDING に戻して次回に備える
            queue.resetToPending(episodeId)
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (queue.isStillWanted(episodeId)) queue.markFailed(episodeId)
            DownloadOutcome.Failed(e)
        }
    }

    private suspend fun replace(
        item: EpisodeWithState,
        isNowPlaying: (EpisodeId) -> Boolean,
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit,
    ): DownloadOutcome {
        val episodeId = item.episode.id
        val sourceId = item.episode.sourceItemId
        val targetPath = item.localFile?.path
        if (sourceId == null || targetPath == null) {
            // 起こらないはず（予約は取得元 ID とパスのある DONE の行だけ）。印を下ろして Worker が同じ行を拾い続けないようにする
            queue.abandonReplace(episodeId)
            return DownloadOutcome.Failed(IllegalStateException("no source id or path"))
        }
        val target = File(targetPath)
        val part = File(targetPath + RoomDownloadRepository.PART_SUFFIX)

        queue.markReplaceRunning(episodeId)
        return try {
            withContext(Dispatchers.IO) {
                // 古い版の書きかけの続きは要求しない。いつも頭から
                part.delete()
                source.openDownload(sourceId, rangeStart = 0).use { stream ->
                    var written = 0L
                    FileOutputStream(part, false).use { out ->
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
                                if (!queue.isReplaceStillWanted(episodeId)) return@withContext cancelled(part)
                                onProgress(written, stream.totalBytes)
                            }
                        }
                    }
                    if (!queue.isReplaceStillWanted(episodeId)) return@withContext cancelled(part)
                    val total = stream.totalBytes
                    if (total != null && written != total) throw IOException("incomplete: $written of $total bytes")
                    if (isNowPlaying(episodeId)) {
                        // 聴いている回のファイルは差し替えない（プレイヤーが同じパスを開き直すと、古い目次で新しいファイルを読みうる）
                        part.delete()
                        queue.abandonReplace(episodeId)
                        return@withContext DownloadOutcome.Postponed
                    }
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    queue.markReplaced(episodeId, target.length())
                    onProgress(written, total ?: written)
                    DownloadOutcome.Done(target.absolutePath, target.length())
                }
            }
        } catch (e: SourceException.Unauthorized) {
            // 認証切れは呼び出し側（Worker）がログアウトへ導く。差し替えはやめ、次の同期で見分け直す
            part.delete()
            queue.abandonReplace(episodeId)
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            part.delete()
            queue.abandonReplace(episodeId)
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
