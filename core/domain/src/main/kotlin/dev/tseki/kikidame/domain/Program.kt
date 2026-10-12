package dev.tseki.kikidame.domain
import kotlin.time.Instant
/** 番組。Jellyfin の MusicAlbum に対応する。 */
data class Program(
    val id: ProgramId,
    val sourceItemId: SourceItemId?,
    val name: String,
    /** 配信元。MusicAlbum の AlbumArtist、手元では番組フォルダの親フォルダ名。 */
    val publisherName: String?,
    val syncEnabled: Boolean = false,
    val retentionRule: RetentionRule = RetentionRule(),
    /** 消失（CONTEXT.md）: 取得元の番組一覧に無く突合でも結び直せなかったと最初に分かった日時。null なら取得元に在る。 */
    val goneSince: Instant? = null,
    /** よく聴く（CONTEXT.md）: 利用者が特に聴く番組に付ける印。固定・同期対象とは独立で、同期にもダウンロードにも影響しない。 */
    val starred: Boolean = false,
    /** 番組の画像（CONTEXT.md）の、手元のファイル。取得元に画像が無い・まだ取れていないなら null（ADR 0012）。 */
    val image: ProgramImage? = null,
) {
    val isGone: Boolean get() = goneSince != null
}
/**
 * 手元に持っている番組の画像（ADR 0012、#264）。同期のときに取得元から取ってアプリの領域に置いたファイル。
 * [path] は絶対パス。画像が変わると別の名前のファイルになる（表示側のキャッシュが古い画像を出さないように）。
 */
data class ProgramImage(val path: String)
/** 保持ルール。同期のときに [SyncPlanner] が適用する（保存した瞬間には何も消えない）。 */
data class RetentionRule(
    /** 最新 N 回まで保持。null は上限なし。 */
    val keepLatest: Int? = null,
    val deleteAfterPlayed: Boolean = false,
)
