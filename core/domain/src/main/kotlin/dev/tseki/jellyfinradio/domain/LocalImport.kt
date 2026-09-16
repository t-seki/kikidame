package dev.tseki.jellyfinradio.domain
import kotlin.time.Duration
import kotlin.time.Instant
/** シードが走査した 1 ファイル。サーバを経由していないので [Episode.serverItemId] は持たない。 */
data class ScannedEpisode(
    val stationName: String,
    val programName: String,
    /** ファイル名（拡張子なし）。 */
    val title: String,
    val path: String,
    val airedAt: Instant,
    val runtime: Duration,
    val sizeBytes: Long,
    val container: String,
)
data class ImportResult(val addedPrograms: Int, val addedEpisodes: Int, val skippedEpisodes: Int)
/**
 * 手元のファイルから番組・各回を作る取り込み。追加専用・べき等:
 * 番組は (放送局, 番組名)、各回は [ScannedEpisode.path] をキーに、既存行は触らない。
 */
interface LocalImportRepository {
    suspend fun import(scanned: List<ScannedEpisode>): ImportResult
}
