package dev.tseki.jellyfinradio.domain
import kotlin.time.Instant
/** 番組。Jellyfin の MusicAlbum に対応する。 */
data class Program(
    val id: ProgramId,
    val serverItemId: ServerItemId?,
    val name: String,
    /** 放送局。MusicAlbum の AlbumArtist、手元では番組フォルダの親フォルダ名。 */
    val stationName: String?,
    val syncEnabled: Boolean = false,
    val retentionRule: RetentionRule = RetentionRule(),
    /** 消失（CONTEXT.md）: サーバの番組一覧に無く突合でも結び直せなかったと最初に分かった日時。null ならサーバに在る。 */
    val goneSince: Instant? = null,
    /** よく聴く（CONTEXT.md）: 番組一覧で上の節に出す表示用の印。固定・同期対象とは独立。 */
    val starred: Boolean = false,
) {
    val isGone: Boolean get() = goneSince != null
}
/** 保持ルール。同期のときに [SyncPlanner] が適用する（保存した瞬間には何も消えない）。 */
data class RetentionRule(
    /** 最新 N 回まで保持。null は上限なし。 */
    val keepLatest: Int? = null,
    val deleteAfterPlayed: Boolean = false,
)
