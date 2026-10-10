package dev.tseki.kikidame.domain

import kotlin.time.Duration
import kotlin.time.Instant

/** 取得元（CONTEXT.md）上の番組の、取り込みに必要な部分だけのスナップショット。Jellyfin では MusicAlbum。 */
data class SourceProgram(
    val sourceId: SourceItemId,
    val name: String,
    /** 配信元。Jellyfin では MusicAlbum の AlbumArtist。無ければ null。 */
    val publisherName: String?,
)

/** 取得元上の各回。Jellyfin では Audio。 */
data class SourceEpisode(
    val sourceId: SourceItemId,
    val programSourceId: SourceItemId,
    val title: String,
    val publishedAt: Instant,
    val addedAt: Instant?,
    val runtime: Duration,
    /** 取得元が返さない場合は null（手元の値を残す）。 */
    val sizeBytes: Long?,
    val container: String,
    /** 出演者（Jellyfin では `Artists`）。取得元が返さなければ空（空で上書きする。サイズと違い「返さない」と「無い」を区別しない）。 */
    val performers: List<String> = emptyList(),
    /**
     * 共有フォルダでの、取得元のファイルのサイズ（バイト）。Jellyfin では null。
     * 更新日時（[sourceModifiedAt]）・取得元 ID（パス）と合わせて、タグを読み直すかの判定に使う（#197）。
     * [sizeBytes] はダウンロードの完了時に実サイズで上書きされるので、判定には使わない。
     */
    val sourceFileSize: Long? = null,
    /** 共有フォルダでの、取得元のファイルの更新日時。Jellyfin では null。 */
    val sourceModifiedAt: Instant? = null,
)

/**
 * 1 回の取得で得た取得元の一覧。番組に属さない各回は含めない。
 * [scope] が [SnapshotScope.Library] なら完全な一覧（削除と結び直しの権限を持つ、ADR 0004 / 0005）、
 * [SnapshotScope.Program] なら 1 番組分（その番組の各回については完全）。
 */
data class SourceSnapshot(
    val programs: List<SourceProgram>,
    val episodes: List<SourceEpisode>,
    val scope: SnapshotScope = SnapshotScope.Library,
)
sealed interface SnapshotScope {
    /** ライブラリ全体。番組の結び直し・消失の判定・各回の削除ができる。 */
    data object Library : SnapshotScope
    /** 1 番組分。その番組の各回については完全なので結び直しと削除ができるが、番組一覧は無いので番組の結び直しはしない。 */
    data class Program(val programSourceId: SourceItemId) : SnapshotScope
}
