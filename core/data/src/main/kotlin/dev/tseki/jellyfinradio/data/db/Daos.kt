package dev.tseki.jellyfinradio.data.db
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import dev.tseki.jellyfinradio.domain.DownloadState
import kotlinx.coroutines.flow.Flow
data class ProgramSummaryRow(
    @Embedded val program: ProgramEntity,
    val episodeCount: Int,
    val localEpisodeCount: Int,
    val latestAiredAt: Long?,
)
/** 突合に必要な列だけ。 */
data class ProgramKeyRow(val id: Long, val serverItemId: String?, val stationName: String?, val name: String)
data class EpisodeKeyRow(val id: Long, val serverItemId: String?, val programId: Long, val title: String)
@Dao
interface ProgramDao {
    @Query(
        """
        SELECT p.*, COUNT(e.id) AS episodeCount,
               SUM(CASE WHEN lf.state = 'DONE' AND lf.path IS NOT NULL THEN 1 ELSE 0 END) AS localEpisodeCount,
               MAX(e.airedAt) AS latestAiredAt
        FROM programs p
          LEFT JOIN episodes e ON e.programId = p.id
          LEFT JOIN local_files lf ON lf.episodeId = e.id
        GROUP BY p.id
        ORDER BY latestAiredAt DESC, p.name ASC
        """,
    )
    fun observeSummaries(): Flow<List<ProgramSummaryRow>>
    @Query("SELECT * FROM programs WHERE id = :id")
    fun observeById(id: Long): Flow<ProgramEntity?>
    /** シードの同一性キー。突合でサーバ ID が付いた後も同じ番組に足す。 */
    @Query("SELECT * FROM programs WHERE stationName IS :stationName AND name = :name ORDER BY id LIMIT 1")
    suspend fun findByStationAndName(stationName: String?, name: String): ProgramEntity?
    @Query("SELECT * FROM programs WHERE serverItemId = :serverItemId")
    suspend fun findByServerItemId(serverItemId: String): ProgramEntity?
    @Query("SELECT id, serverItemId, stationName, name FROM programs")
    suspend fun listKeys(): List<ProgramKeyRow>
    @Query("UPDATE programs SET serverItemId = :serverItemId WHERE id = :id")
    suspend fun setServerItemId(id: Long, serverItemId: String)
    @Query("UPDATE programs SET name = :name, stationName = :stationName WHERE id = :id")
    suspend fun updateNames(id: Long, name: String, stationName: String?)
    @Insert
    suspend fun insert(program: ProgramEntity): Long
    @Query("DELETE FROM programs")
    suspend fun deleteAll()
    @Query("DELETE FROM programs WHERE id = :id")
    suspend fun deleteById(id: Long)
    @Query("SELECT * FROM programs WHERE id = :id")
    suspend fun findById(id: Long): ProgramEntity?
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
    @Query("SELECT * FROM episodes WHERE serverItemId = :serverItemId")
    suspend fun findByServerItemId(serverItemId: String): EpisodeEntity?
    @Query("SELECT id, serverItemId, programId, title FROM episodes")
    suspend fun listKeys(): List<EpisodeKeyRow>
    @Query("UPDATE episodes SET serverItemId = :serverItemId WHERE id = :id")
    suspend fun setServerItemId(id: Long, serverItemId: String)
    @Insert
    suspend fun insert(episode: EpisodeEntity): Long
    @Update
    suspend fun update(episode: EpisodeEntity)
    @Query("UPDATE episodes SET sizeBytes = :sizeBytes WHERE id = :id")
    suspend fun updateSize(id: Long, sizeBytes: Long)
    @Query("DELETE FROM episodes WHERE id = :id")
    suspend fun deleteById(id: Long)
    @Query("SELECT COUNT(*) FROM episodes WHERE programId = :programId")
    suspend fun countByProgram(programId: Long): Int
}
@Dao
interface LocalFileDao {
    @Query("SELECT * FROM local_files WHERE path = :path")
    suspend fun findByPath(path: String): LocalFileEntity?
    @Query("SELECT * FROM local_files WHERE episodeId = :episodeId")
    suspend fun findByEpisode(episodeId: Long): LocalFileEntity?
    @Query("SELECT * FROM local_files WHERE state = :state")
    suspend fun listByState(state: DownloadState): List<LocalFileEntity>
    /** キューの先頭: PENDING を放送日の新しい順（同着はタイトル・ID）。 */
    @Query(
        """
        SELECT lf.* FROM local_files lf JOIN episodes e ON e.id = lf.episodeId
        WHERE lf.state = 'PENDING'
        ORDER BY e.airedAt DESC, e.title ASC, e.id ASC LIMIT 1
        """,
    )
    suspend fun nextPending(): LocalFileEntity?
    @Query("UPDATE local_files SET state = 'PENDING' WHERE state = 'FAILED' AND attemptCount < :maxAttempts")
    suspend fun requeueFailed(maxAttempts: Int)
    @Upsert
    suspend fun upsert(localFile: LocalFileEntity)
    @Query("DELETE FROM local_files WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: Long)
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
