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
    val sizeBytes: Long,
    val container: String,
)

/** 1 回の取得で得たライブラリ全体。番組に属さない各回は含めない。 */
data class ServerSnapshot(
    val programs: List<ServerProgram>,
    val episodes: List<ServerEpisode>,
)

/** サーバの日時（`PremiereDate` → `DateCreated`）から放送日を作る。日付部分だけ取り JST 0 時に置く。 */
fun serverAiredAt(premiereDate: LocalDate?, dateCreated: LocalDate): Instant =
    (premiereDate ?: dateCreated).atStartOfDayIn(AiredAt.ZONE)
