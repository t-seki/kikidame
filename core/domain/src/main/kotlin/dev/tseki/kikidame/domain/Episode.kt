package dev.tseki.kikidame.domain
import kotlin.time.Duration
import kotlin.time.Instant
/** 各回。Jellyfin の Audio に対応する。 */
data class Episode(
    val id: EpisodeId,
    val serverItemId: ServerItemId?,
    val programId: ProgramId,
    val title: String,
    /** 公開日。日単位なので同じ番組内で衝突しうる（順序は [EpisodeOrder]）。 */
    val publishedAt: Instant,
    /** 取り込み日時。サーバを経由していない各回は null。 */
    val addedAt: Instant?,
    val runtime: Duration,
    val sizeBytes: Long,
    val container: String,
    /** 出演者（Jellyfin の Audio の `Artists`）。回ごとに変わる。番組の配信元（AlbumArtist）とは別。サーバに無ければ空。 */
    val performers: List<String> = emptyList(),
)
/**
 * 各回の正規の並び順: 公開日 → タイトルの辞書順 → ローカル ID。連続再生はこの順（古い順）。
 * 各回一覧は [newestFirst]（公開日だけ逆順、同着のタイトル順はそのまま）。
 */
object EpisodeOrder : Comparator<Episode> by compareBy<Episode>({ it.publishedAt }, { it.title }, { it.id.value }) {
    /** 公開日の新しい順。同じ公開日の中はタイトルの辞書順 → ローカル ID（正規の順と同じ）。 */
    val newestFirst: Comparator<Episode> =
        compareByDescending<Episode> { it.publishedAt }.thenBy { it.title }.thenBy { it.id.value }
}
