package dev.tseki.jellyfinradio.data.repository

import androidx.room.withTransaction
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
            if (db.localFileDao().findByEpisode(episodeId.value) != null) return@withTransaction
            val row = db.episodeDao().findById(episodeId.value) ?: return@withTransaction
            val episode = row.episode
            if (episode.serverItemId == null) return@withTransaction
            val program = db.programDao().findById(episode.programId) ?: return@withTransaction
            val path = uniqueTarget(EpisodeFileName.relativePath(program.stationName, program.name, episode.title, episode.container))
            db.localFileDao().upsert(
                LocalFileEntity(
                    episodeId = episodeId.value,
                    state = DownloadState.PENDING,
                    path = path.absolutePath,
                    pinned = true,
                ),
            )
        }
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
        if (row.state == DownloadState.FAILED) db.localFileDao().upsert(row.copy(state = DownloadState.PENDING))
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
                LocalDeletionScope.EPISODE -> {
                    val programId = row.episode.programId
                    db.episodeDao().deleteById(episodeId.value) // local_files / playback_states は cascade
                    val program = db.programDao().findById(programId)
                    if (program != null && program.serverItemId == null && db.episodeDao().countByProgram(programId) == 0) {
                        db.programDao().deleteById(programId)
                    }
                }
            }
        }
        path?.let { p ->
            withContext(Dispatchers.IO) {
                File(p).delete()
                File(p + PART_SUFFIX).delete()
            }
        }
        return scope
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
