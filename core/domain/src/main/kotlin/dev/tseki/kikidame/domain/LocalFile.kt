package dev.tseki.kikidame.domain
import kotlin.time.Instant
enum class DownloadState { PENDING, RUNNING, DONE, FAILED }
/** 手元のファイルの状態。ダウンロード状態の唯一の正（ADR 0002）。 */
data class LocalFile(
    val episodeId: EpisodeId,
    val state: DownloadState,
    val path: String?,
    /** 固定。保持ルールの対象外（手動ダウンロード）。 */
    val pinned: Boolean,
    val attemptCount: Int = 0,
    val lastAttemptAt: Instant? = null,
    val downloadedAt: Instant? = null,
    /**
     * **差し替え**（ADR 0011）のダウンロードが予約されているか、落としている最中か。[state] は DONE のまま（今のファイルで聴ける）。
     * ダウンロードの実行側は、これが true の行を差し替えとして落とす。
     */
    val replacing: Boolean = false,
)
