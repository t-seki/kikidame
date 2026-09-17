package dev.tseki.jellyfinradio.data.repository

import androidx.room.withTransaction
import dev.tseki.jellyfinradio.data.db.EpisodeEntity
import dev.tseki.jellyfinradio.data.db.JellyfinRadioDatabase
import dev.tseki.jellyfinradio.data.db.LocalFileEntity
import dev.tseki.jellyfinradio.data.db.toDomain
import dev.tseki.jellyfinradio.data.files.EpisodesDirectory
import dev.tseki.jellyfinradio.domain.DownloadQueue
import dev.tseki.jellyfinradio.domain.DownloadRepository
import dev.tseki.jellyfinradio.domain.DownloadState
import dev.tseki.jellyfinradio.domain.EpisodeFileName
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.EpisodeWithState
import dev.tseki.jellyfinradio.domain.LocalDeletionScope
import dev.tseki.jellyfinradio.domain.deletionScopeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * ダウンロードの状態（`local_files`）と手元のファイルをまとめて扱う。
 * 削除の規則は [deletionScopeFor]: サーバ ID がある回はファイルと行だけ、無い回は各回ごと。
 */
@Singleton
class RoomDownloadRepository @Inject constructor(
    private val db: JellyfinRadioDatabase,
    private val directory: EpisodesDirectory,
    private val clock: Clock,
) : DownloadRepository, DownloadQueue {

    override suspend fun enqueue(episodeId: EpisodeId) {
        db.withTransaction {
            val existing = db.localFileDao().findByEpisode(episodeId.value)
            if (existing != null) {
                // 同期が予約した行を手動に格上げする（手動ダウンロード = 固定）
                if (!existing.pinned) db.localFileDao().upsert(existing.copy(pinned = true))
                return@withTransaction
            }
            insertPending(episodeId, pinned = true)
        }
    }

    override suspend fun enqueueForSync(episodeIds: List<EpisodeId>) {
        db.withTransaction {
            for (id in episodeIds) {
                if (db.localFileDao().findByEpisode(id.value) != null) continue
                insertPending(id, pinned = false)
            }
        }
    }

    /** トランザクション内で呼ぶ。サーバ ID の無い回は落とせないので何もしない。 */
    private suspend fun insertPending(episodeId: EpisodeId, pinned: Boolean) {
        val row = db.episodeDao().findById(episodeId.value) ?: return
        val episode = row.episode
        if (episode.serverItemId == null) return
        val program = db.programDao().findById(episode.programId) ?: return
        val path = uniqueTarget(EpisodeFileName.relativePath(program.stationName, program.name, episode.title, episode.container))
        db.localFileDao().upsert(
            LocalFileEntity(
                episodeId = episodeId.value,
                state = DownloadState.PENDING,
                path = path.absolutePath,
                pinned = pinned,
                enqueuedAt = clock.now(),
            ),
        )
    }

    /** 同名のファイルが既にあれば ` (2)`, ` (3)` … を付ける。 */
    private fun uniqueTarget(relativePath: String): File {
        var candidate = directory.resolve(relativePath)
        var n = 2
        while (candidate.exists() || File(candidate.path + PART_SUFFIX).exists()) {
            candidate = directory.resolve(EpisodeFileName.withSuffix(relativePath, n++))
        }
        return candidate
    }

    override suspend fun cancel(episodeId: EpisodeId) {
        val row = db.localFileDao().findByEpisode(episodeId.value) ?: return
        if (row.state == DownloadState.DONE) return
        db.localFileDao().delete(episodeId.value)
        row.path?.let { withContext(Dispatchers.IO) { File(it + PART_SUFFIX).delete() } }
    }

    override suspend fun retry(episodeId: EpisodeId) {
        val row = db.localFileDao().findByEpisode(episodeId.value) ?: return
        // 再試行は列の末尾に付け直す
        if (row.state == DownloadState.FAILED) db.localFileDao().upsert(row.copy(state = DownloadState.PENDING, enqueuedAt = clock.now()))
    }

    override suspend fun unpin(episodeId: EpisodeId) {
        val row = db.localFileDao().findByEpisode(episodeId.value) ?: return
        if (row.pinned) db.localFileDao().upsert(row.copy(pinned = false))
    }

    override suspend fun deleteLocal(episodeId: EpisodeId): LocalDeletionScope {
        val row = db.episodeDao().findById(episodeId.value) ?: return LocalDeletionScope.FILE_ONLY
        val scope = deletionScopeFor(row.episode.toDomain())
        val path = row.localFile?.path
        db.withTransaction {
            when (scope) {
                LocalDeletionScope.FILE_ONLY -> db.localFileDao().delete(episodeId.value)
                LocalDeletionScope.EPISODE -> deleteEpisodeRow(row.episode)
            }
        }
        deleteFiles(path)
        return scope
    }

    override suspend fun removeEpisode(episodeId: EpisodeId) {
        val row = db.episodeDao().findById(episodeId.value) ?: return
        db.withTransaction { deleteEpisodeRow(row.episode) }
        deleteFiles(row.localFile?.path)
    }

    /** トランザクション内で呼ぶ。`local_files` / `playback_states` は cascade。各回が 0 になったサーバ ID 無しの番組も消す。 */
    private suspend fun deleteEpisodeRow(episode: EpisodeEntity) {
        val programId = episode.programId
        db.episodeDao().deleteById(episode.id)
        val program = db.programDao().findById(programId)
        if (program != null && program.serverItemId == null && db.episodeDao().countByProgram(programId) == 0) {
            db.programDao().deleteById(programId)
        }
    }

    /** ファイル I/O はトランザクションの外で。 */
    private suspend fun deleteFiles(path: String?) {
        path ?: return
        withContext(Dispatchers.IO) {
            File(path).delete()
            File(path + PART_SUFFIX).delete()
        }
    }

    override suspend fun reconcileMissingFiles(): Int {
        val done = db.localFileDao().listByState(DownloadState.DONE)
        val missing = withContext(Dispatchers.IO) { done.filter { it.path == null || !File(it.path).isFile } }
        for (row in missing) deleteLocal(EpisodeId(row.episodeId))
        return missing.size
    }

    override suspend fun ensureFilePresent(episodeId: EpisodeId): Boolean {
        val row = db.localFileDao().findByEpisode(episodeId.value) ?: return false
        if (row.state != DownloadState.DONE) return false
        val present = withContext(Dispatchers.IO) { row.path != null && File(row.path).isFile }
        if (!present) deleteLocal(episodeId)
        return present
    }

    // --- DownloadQueue（Worker 側） ---

    override suspend fun nextPending(): EpisodeWithState? {
        val row = db.localFileDao().nextPending() ?: return null
        return db.episodeDao().findById(row.episodeId)?.toDomain()
    }

    override suspend fun requeueFailed(maxAttempts: Int) = db.localFileDao().requeueFailed(maxAttempts)

    override suspend fun resetRunning() = db.localFileDao().resetRunning()

    override suspend fun resetToPending(episodeId: EpisodeId) {
        val row = db.localFileDao().findByEpisode(episodeId.value) ?: return
        if (row.state == DownloadState.RUNNING) db.localFileDao().upsert(row.copy(state = DownloadState.PENDING))
    }

    override suspend fun markRunning(episodeId: EpisodeId) {
        val row = db.localFileDao().findByEpisode(episodeId.value) ?: return
        db.localFileDao().upsert(row.copy(state = DownloadState.RUNNING, lastAttemptAt = clock.now()))
    }

    override suspend fun markDone(episodeId: EpisodeId, path: String, sizeBytes: Long) {
        db.withTransaction {
            val row = db.localFileDao().findByEpisode(episodeId.value) ?: return@withTransaction
            db.localFileDao().upsert(row.copy(state = DownloadState.DONE, path = path, downloadedAt = clock.now()))
            db.episodeDao().updateSize(episodeId.value, sizeBytes)
        }
    }

    override suspend fun markFailed(episodeId: EpisodeId) {
        val row = db.localFileDao().findByEpisode(episodeId.value) ?: return
        db.localFileDao().upsert(row.copy(state = DownloadState.FAILED, attemptCount = row.attemptCount + 1, lastAttemptAt = clock.now()))
    }

    override suspend fun isStillWanted(episodeId: EpisodeId): Boolean {
        val row = db.localFileDao().findByEpisode(episodeId.value) ?: return false
        return row.state == DownloadState.RUNNING || row.state == DownloadState.PENDING
    }

    companion object {
        const val PART_SUFFIX = ".part"
    }
}
