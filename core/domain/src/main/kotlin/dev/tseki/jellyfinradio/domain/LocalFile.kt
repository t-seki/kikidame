package dev.tseki.jellyfinradio.domain
import kotlin.time.Instant
enum class DownloadState { PENDING, RUNNING, DONE, FAILED }
/** 手元のファイルの状態。ダウンロード状態の唯一の正（ADR 0003）。 */
data class LocalFile(
    val episodeId: EpisodeId,
    val state: DownloadState,
    val path: String?,
    /** 固定。保持ルールの対象外（手動ダウンロード）。 */
    val pinned: Boolean,
    val attemptCount: Int = 0,
    val lastAttemptAt: Instant? = null,
    val downloadedAt: Instant? = null,
)
