package dev.tseki.kikidame.data.sharedfolder

import dev.tseki.kikidame.data.db.EpisodeDao
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.Ticks
import kotlin.time.Duration
import kotlin.time.Instant

/** 前の走査で取り込んだ回の、タグ由来の値とファイルのサイズ・更新日時（#197）。 */
data class ScannedFile(
    val sizeBytes: Long,
    val modifiedAt: Instant,
    val title: String,
    val publishedAt: Instant,
    val runtime: Duration,
    val performers: List<String>,
)

/** 前の走査の結果を、取得元 ID（相対パス）で引けるように読む。タグを読み直すかの判定に使う（epic #195 の決定 8）。 */
fun interface ScannedFiles {
    suspend fun load(): Map<SourceItemId, ScannedFile>
}

/** 回の行（`episodes` の `sourceFileSize` / `sourceModifiedAt` を持つ行）から読む。 */
class RoomScannedFiles(private val dao: EpisodeDao) : ScannedFiles {
    override suspend fun load(): Map<SourceItemId, ScannedFile> =
        dao.listScannedFiles().associate { row ->
            SourceItemId(row.sourceItemId) to ScannedFile(
                sizeBytes = row.sourceFileSize,
                modifiedAt = row.sourceModifiedAt,
                title = row.title,
                publishedAt = row.publishedAt,
                runtime = Ticks.toDuration(row.runtimeTicks),
                performers = row.performers,
            )
        }
}
