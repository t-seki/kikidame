package dev.tseki.jellyfinradio.data.db
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
data class ProgramSummaryRow(
    @Embedded val program: ProgramEntity,
    val episodeCount: Int,
    val latestAiredAt: Long?,
)
@Dao
interface ProgramDao {
    @Query(
        """
        SELECT p.*, COUNT(e.id) AS episodeCount, MAX(e.airedAt) AS latestAiredAt
        FROM programs p LEFT JOIN episodes e ON e.programId = p.id
        GROUP BY p.id
        ORDER BY latestAiredAt DESC, p.name ASC
        """,
    )
    fun observeSummaries(): Flow<List<ProgramSummaryRow>>
    @Query("SELECT * FROM programs WHERE id = :id")
    fun observeById(id: Long): Flow<ProgramEntity?>
    @Query("SELECT * FROM programs WHERE stationName IS :stationName AND name = :name")
    suspend fun findByStationAndName(stationName: String?, name: String): ProgramEntity?
    @Insert
    suspend fun insert(program: ProgramEntity): Long
}
/** 各回とその手元の状態。`@Relation` の to-one は行が無ければ null。 */
data class EpisodeRow(
    @Embedded val episode: EpisodeEntity,
    @Relation(parentColumn = "id", entityColumn = "episodeId") val localFile: LocalFileEntity?,
    @Relation(parentColumn = "id", entityColumn = "episodeId") val playback: PlaybackStateEntity?,
)
@Dao
interface EpisodeDao {
    @Transaction
    @Query("SELECT * FROM episodes WHERE programId = :programId")
    fun observeByProgram(programId: Long): Flow<List<EpisodeRow>>
    @Transaction
    @Query("SELECT * FROM episodes WHERE programId = :programId")
    suspend fun listByProgram(programId: Long): List<EpisodeRow>
    @Transaction
    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun findById(id: Long): EpisodeRow?
    @Insert
    suspend fun insert(episode: EpisodeEntity): Long
}
@Dao
interface LocalFileDao {
    @Query("SELECT * FROM local_files WHERE path = :path")
    suspend fun findByPath(path: String): LocalFileEntity?
    @Upsert
    suspend fun upsert(localFile: LocalFileEntity)
}
@Dao
interface PlaybackStateDao {
    @Query("SELECT * FROM playback_states WHERE episodeId = :episodeId")
    suspend fun findByEpisode(episodeId: Long): PlaybackStateEntity?
    @Query("SELECT * FROM playback_states WHERE episodeId = :episodeId")
    fun observeByEpisode(episodeId: Long): Flow<PlaybackStateEntity?>
    @Upsert
    suspend fun upsert(state: PlaybackStateEntity)
}
