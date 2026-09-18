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
import kotlin.time.Instant
data class ProgramSummaryRow(
    @Embedded val program: ProgramEntity,
    val episodeCount: Int,
    val localEpisodeCount: Int,
    val latestAiredAt: Long?,
)
/** 突合に必要な列だけ。 */
data class ProgramKeyRow(val id: Long, val serverItemId: String?, val stationName: String?, val name: String)
data class EpisodeKeyRow(val id: Long, val serverItemId: String?, val programId: Long, val title: String, val airedAt: Instant, val runtimeTicks: Long)
/** 同期の判断に必要な列だけ（`SyncPlanner` の入力）。`local_files` / `playback_states` が無ければ null。 */
data class EpisodeSyncRow(
    val id: Long,
    val serverItemId: String?,
    val programId: Long,
    val airedAt: Instant,
    val title: String,
    val pinned: Boolean?,
    val hasLocalFile: Boolean,
    val played: Boolean?,
)
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
    /** (放送局, 番組名) で探す。一意ではない。本体の突合は [listKeys] を [dev.tseki.jellyfinradio.domain.LibraryMatching] に渡す方式で、これは使わない（テストの下ごしらえ用）。 */
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
    @Query("UPDATE programs SET syncEnabled = :syncEnabled, keepLatest = :keepLatest, deleteAfterPlayed = :deleteAfterPlayed WHERE id = :id")
    suspend fun updateSync(id: Long, syncEnabled: Boolean, keepLatest: Int?, deleteAfterPlayed: Boolean)
    @Query("SELECT * FROM programs")
    suspend fun listAll(): List<ProgramEntity>
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
    @Query("SELECT id, serverItemId, programId, title, airedAt, runtimeTicks FROM episodes")
    suspend fun listKeys(): List<EpisodeKeyRow>
    /** 全各回を 1 クエリで（番組ごとに @Relation を引かない）。 */
    @Query(
        """
        SELECT e.id, e.serverItemId, e.programId, e.airedAt, e.title,
               lf.pinned AS pinned, (lf.episodeId IS NOT NULL) AS hasLocalFile, ps.played AS played
        FROM episodes e
          LEFT JOIN local_files lf ON lf.episodeId = e.id
          LEFT JOIN playback_states ps ON ps.episodeId = e.id
        """,
    )
    suspend fun listSyncRows(): List<EpisodeSyncRow>
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
    /**
     * キューの先頭: 手動（固定）が常に先、その中はキューに入れた順（FIFO）。
     * `enqueuedAt` は v3 で足した nullable なので NULL を先頭に来させない。同時刻は放送日の新しい順で安定させる。
     */
    @Query(
        """
        SELECT lf.* FROM local_files lf JOIN episodes e ON e.id = lf.episodeId
        WHERE lf.state = 'PENDING'
        ORDER BY lf.pinned DESC, lf.enqueuedAt IS NULL, lf.enqueuedAt ASC, e.airedAt DESC, e.title ASC, e.id ASC LIMIT 1
        """,
    )
    suspend fun nextPending(): LocalFileEntity?
    /** 固定でない手元の行の数（同期対象を OFF にしたら次の同期で消える回）。 */
    @Query("SELECT COUNT(*) FROM local_files lf JOIN episodes e ON e.id = lf.episodeId WHERE e.programId = :programId AND lf.pinned = 0")
    suspend fun countUnpinnedByProgram(programId: Long): Int
    @Query("UPDATE local_files SET state = 'PENDING' WHERE state = 'FAILED' AND attemptCount < :maxAttempts")
    suspend fun requeueFailed(maxAttempts: Int)
    @Query("UPDATE local_files SET state = 'PENDING' WHERE state = 'RUNNING'")
    suspend fun resetRunning()
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
