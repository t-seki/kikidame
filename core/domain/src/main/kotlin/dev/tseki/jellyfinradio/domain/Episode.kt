package dev.tseki.jellyfinradio.domain
import kotlin.time.Duration
import kotlin.time.Instant
/** 各回。Jellyfin の Audio に対応する。 */
data class Episode(
    val id: EpisodeId,
    val serverItemId: ServerItemId?,
    val programId: ProgramId,
    val title: String,
    /** 放送日。日単位なので同じ番組内で衝突しうる（順序は [EpisodeOrder]）。 */
    val airedAt: Instant,
    /** 取り込み日時。サーバを経由していない各回は null。 */
    val addedAt: Instant?,
    val runtime: Duration,
    val sizeBytes: Long,
    val container: String,
)
/**
 * 各回の正規の並び順: 放送日 → タイトルの辞書順 → ローカル ID。
 * 各回一覧はこの逆順（新しい順）、連続再生はこの順（古い順）で使う。
 */
object EpisodeOrder : Comparator<Episode> by compareBy<Episode>({ it.airedAt }, { it.title }, { it.id.value })
