package dev.tseki.jellyfinradio.domain
/** 番組。Jellyfin の MusicAlbum に対応する。 */
data class Program(
    val id: ProgramId,
    val serverItemId: ServerItemId?,
    val name: String,
    /** 放送局。MusicAlbum の AlbumArtist、手元では番組フォルダの親フォルダ名。 */
    val stationName: String?,
    val syncEnabled: Boolean = false,
    val retentionRule: RetentionRule = RetentionRule(),
)
/** 保持ルール。M1 では保存するだけで適用しない（適用は M3）。 */
data class RetentionRule(
    /** 最新 N 回まで保持。null は上限なし。 */
    val keepLatest: Int? = null,
    val deleteAfterPlayed: Boolean = false,
)
