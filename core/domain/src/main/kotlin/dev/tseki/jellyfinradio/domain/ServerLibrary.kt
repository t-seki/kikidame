package dev.tseki.jellyfinradio.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Duration
import kotlin.time.Instant

/** サーバ上の番組（MusicAlbum）の、取り込みに必要な部分だけのスナップショット。 */
data class ServerProgram(
    val serverId: ServerItemId,
    val name: String,
    /** MusicAlbum の AlbumArtist。無ければ null。 */
    val stationName: String?,
)

/** サーバ上の各回（Audio）。 */
data class ServerEpisode(
    val serverId: ServerItemId,
    val programServerId: ServerItemId,
    val title: String,
    val airedAt: Instant,
    val addedAt: Instant?,
    val runtime: Duration,
    /** サーバが返さない場合は null（手元の値を残す）。 */
    val sizeBytes: Long?,
    val container: String,
)

/**
 * 1 回の取得で得たサーバの一覧。番組に属さない各回は含めない。
 * [scope] が [SnapshotScope.Library] なら完全な一覧（削除と結び直しの権限を持つ、ADR 0004 / 0005）、
 * [SnapshotScope.Program] なら 1 番組分（その番組の各回については完全）。
 */
data class ServerSnapshot(
    val programs: List<ServerProgram>,
    val episodes: List<ServerEpisode>,
    val scope: SnapshotScope = SnapshotScope.Library,
)
sealed interface SnapshotScope {
    /** ライブラリ全体。番組の結び直し・消失の判定・各回の削除ができる。 */
    data object Library : SnapshotScope
    /** 1 番組分。その番組の各回については完全なので結び直しと削除ができるが、番組一覧は無いので番組の結び直しはしない。 */
    data class Program(val programServerId: ServerItemId) : SnapshotScope
}

/** サーバの日時（`PremiereDate` → `DateCreated`）から放送日を作る。日付部分だけ取り JST 0 時に置く。 */
fun serverAiredAt(premiereDate: LocalDate?, dateCreated: LocalDate): Instant =
    (premiereDate ?: dateCreated).atStartOfDayIn(AiredAt.ZONE)
