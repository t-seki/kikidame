package dev.tseki.kikidame.data.repository

import androidx.room.withTransaction
import dev.tseki.kikidame.data.db.EpisodeEntity
import dev.tseki.kikidame.data.db.KikidameDatabase
import dev.tseki.kikidame.data.db.LocalFileEntity
import dev.tseki.kikidame.data.db.UnmatchedRow
import dev.tseki.kikidame.data.db.toDomain
import dev.tseki.kikidame.data.files.EpisodesDirectory
import dev.tseki.kikidame.domain.DownloadQueue
import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.EpisodeFileName
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LocalDeletionScope
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.RemovedUnmatchedPrograms
import dev.tseki.kikidame.domain.UnmatchedPreview
import dev.tseki.kikidame.domain.UnmatchedPrograms
import dev.tseki.kikidame.domain.deletionScopeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * ダウンロードの状態（`local_files`）と手元のファイルをまとめて扱う。
 * 削除の規則は [deletionScopeFor]: 取得元 ID がある回はファイルと行だけ、無い回は各回ごと。
 * 差し替え（ADR 0011）は、DONE の行に `replaceState` を立てて同じ待ち行列に入れる。手元の回を消すと、差し替えも一緒に消える（行と `.part` が消える）。
 */
@Singleton
class RoomDownloadRepository @Inject constructor(
    private val db: KikidameDatabase,
    private val directory: EpisodesDirectory,
    private val clock: Clock,
) : DownloadRepository, DownloadQueue {

    override suspend fun enqueue(episodeId: EpisodeId) {
        db.withTransaction {
            val existing = db.localFileDao().findByEpisode(episodeId.value)
            if (existing != null) {
                // 同期が予約した行を手動に格上げする（手動ダウンロード = 固定）。手動同士の FIFO はタップした時刻で決めるので付け直す
                if (!existing.pinned) db.localFileDao().upsert(existing.copy(pinned = true, enqueuedAt = clock.now()))
                return@withTransaction
            }
            insertPending(episodeId, pinned = true)
        }
    }

    override suspend fun enqueueForSync(episodeIds: List<EpisodeId>): Int = db.withTransaction {
        var inserted = 0
        for (id in episodeIds) {
            if (db.localFileDao().findByEpisode(id.value) != null) continue
            if (insertPending(id, pinned = false)) inserted++
        }
        inserted
    }

    override suspend fun enqueueReplacements(episodeIds: List<EpisodeId>): Int {
        val reserved = ArrayList<String>()
        db.withTransaction {
            val now = clock.now()
            for (id in episodeIds) {
                val version = db.episodeDao().findSourceVersion(id.value)
                if (db.localFileDao().reserveReplace(id.value, version, now) == 0) continue
                db.localFileDao().findByEpisode(id.value)?.path?.let { reserved += it }
            }
        }
        // 古い版の書きかけの続きを要求しないよう、残っている .part を捨てる（ADR 0011）。ファイル I/O はトランザクションの外で
        withContext(Dispatchers.IO) { for (path in reserved) File(path + PART_SUFFIX).delete() }
        return reserved.size
    }

    /** トランザクション内で呼ぶ。取得元 ID の無い回は落とせないので何もしない（false）。予約したときの一覧の版を記録する（ADR 0011）。 */
    private suspend fun insertPending(episodeId: EpisodeId, pinned: Boolean): Boolean {
        val row = db.episodeDao().findById(episodeId.value) ?: return false
        val episode = row.episode
        if (episode.sourceItemId == null) return false
        val program = db.programDao().findById(episode.programId) ?: return false
        val path = uniqueTarget(EpisodeFileName.relativePath(program.publisherName, program.name, episode.title, episode.container))
        db.localFileDao().upsert(
            LocalFileEntity(
                episodeId = episodeId.value,
                state = DownloadState.PENDING,
                path = path.absolutePath,
                pinned = pinned,
                enqueuedAt = clock.now(),
                sourceVersion = episode.sourceVersion,
            ),
        )
        return true
    }

    /**
     * 同名のファイルが既にあれば ` (2)`, ` (3)` … を付ける。ディスクだけでなく `local_files.path`（unique）も見る:
     * 同期は 1 トランザクションで何本も予約するので、ファイルがまだ無くても行が先に取っていることがある
     * （同じ番組に同じタイトルの回、禁止文字の置換で同名になる回）。
     */
    private suspend fun uniqueTarget(relativePath: String): File {
        var candidate = directory.resolve(relativePath)
        var n = 2
        while (candidate.exists() || File(candidate.path + PART_SUFFIX).exists() || db.localFileDao().findByPath(candidate.absolutePath) != null) {
            candidate = directory.resolve(EpisodeFileName.withSuffix(relativePath, n++))
        }
        return candidate
    }

    override suspend fun cancel(episodeId: EpisodeId) {
        // パスを読むだけ。消すかどうか（DONE でないか）は消す文の中で見る: 読んだ後に完了した回の行を消さない
        val row = db.localFileDao().findByEpisode(episodeId.value) ?: return
        if (db.localFileDao().deleteUnlessDone(episodeId.value) == 0) return
        row.path?.let { withContext(Dispatchers.IO) { File(it + PART_SUFFIX).delete() } }
    }

    override suspend fun retry(episodeId: EpisodeId) {
        // 再試行は列の末尾に付け直す
        db.localFileDao().retryFailed(episodeId.value, clock.now())
    }

    override suspend fun pin(episodeId: EpisodeId) {
        db.localFileDao().pin(episodeId.value)
    }

    override suspend fun unpin(episodeId: EpisodeId) {
        db.localFileDao().unpin(episodeId.value)
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

    /** 番組の行ごと消す。手元の音声ファイルと番組の画像（ADR 0009）のファイルも消す。画像を消せなくても削除は止めない（残ったファイルは次の全走査の掃除で消える）。 */
    override suspend fun removeProgram(programId: ProgramId) {
        val paths = db.localFileDao().listPathsByProgram(programId.value)
        val imagePath = db.programDao().findById(programId.value)?.imagePath
        db.withTransaction { db.programDao().deleteById(programId.value) } // episodes → local_files / playback_states は cascade
        for (p in paths) deleteFiles(p)
        imagePath?.let { withContext(Dispatchers.IO) { File(it).delete() } }
    }

    override suspend fun unmatchedPrograms(except: EpisodeId?): UnmatchedPreview {
        val keep = exceptProgramId(except)
        val ids = db.programDao().listUnmatchedIds().filter { it != keep }
        return UnmatchedPreview(db.programDao().countByIds(ids).toDomain(), ids.map(::ProgramId))
    }

    override fun observeUnmatchedPrograms(): Flow<UnmatchedPrograms> =
        db.programDao().observeUnmatched().map { it.toDomain() }

    override suspend fun removeUnmatchedPrograms(programIds: List<ProgramId>, except: EpisodeId?): RemovedUnmatchedPrograms {
        val keep = exceptProgramId(except)
        val stillUnmatched = db.programDao().listUnmatchedIds().toSet()
        val targets = programIds.map { it.value }.filter { it in stillUnmatched }
        val removable = targets.filter { it != keep }
        for (id in removable) removeProgram(ProgramId(id))
        return RemovedUnmatchedPrograms(removed = removable.size, kept = targets.size - removable.size)
    }

    /** 聴いている回の番組の id。回が無い・指定が無ければ、どの番組にも当たらない値。 */
    private suspend fun exceptProgramId(except: EpisodeId?): Long =
        except?.let { db.episodeDao().findById(it.value)?.episode?.programId } ?: NO_PROGRAM

    private fun UnmatchedRow.toDomain() = UnmatchedPrograms(programCount, localEpisodeCount, pinnedEpisodeCount)

    /** トランザクション内で呼ぶ。`local_files` / `playback_states` は cascade。各回が 0 になった取得元 ID 無しの番組も消す。 */
    private suspend fun deleteEpisodeRow(episode: EpisodeEntity) {
        val programId = episode.programId
        db.episodeDao().deleteById(episode.id)
        val program = db.programDao().findById(programId)
        if (program != null && program.sourceItemId == null && db.episodeDao().countByProgram(programId) == 0) {
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
        db.localFileDao().resetToPending(episodeId.value)
    }

    override suspend fun markRunning(episodeId: EpisodeId) {
        db.localFileDao().markRunning(episodeId.value, clock.now())
    }

    override suspend fun markDone(episodeId: EpisodeId, path: String, sizeBytes: Long) {
        db.withTransaction {
            val updated = updateRow(episodeId) { row -> row.copy(state = DownloadState.DONE, path = path, downloadedAt = clock.now()) }
            if (updated) db.episodeDao().updateSize(episodeId.value, sizeBytes)
        }
    }

    override suspend fun markFailed(episodeId: EpisodeId) {
        db.localFileDao().markFailed(episodeId.value, clock.now())
    }

    /**
     * `local_files` の行を読み、あれば [change] で変えて書く。行が無いときと、[change] が null を返したときは書かない。書いたら true。
     * トランザクションは張らない（囲むかどうかは呼ぶ側が決める）。読んでから書くまでの間に別の経路が書くと、その書き込みを古い行で戻し、
     * 消された行を生き返らせるので、トランザクションの中でだけ呼ぶ（今は [markDone] だけ）。ほかの 1 回の更新は `LocalFileDao` の 1 つの文で行う
     */
    private suspend fun updateRow(episodeId: EpisodeId, change: (LocalFileEntity) -> LocalFileEntity?): Boolean {
        val row = db.localFileDao().findByEpisode(episodeId.value) ?: return false
        val updated = change(row) ?: return false
        db.localFileDao().upsert(updated)
        return true
    }

    override suspend fun isStillWanted(episodeId: EpisodeId): Boolean {
        val row = db.localFileDao().findByEpisode(episodeId.value) ?: return false
        return row.state == DownloadState.RUNNING || row.state == DownloadState.PENDING
    }

    // --- 差し替え（ADR 0011）。行は DONE のまま ---

    override suspend fun markReplaceRunning(episodeId: EpisodeId) {
        db.localFileDao().markReplaceRunning(episodeId.value, clock.now())
    }

    override suspend fun markReplaced(episodeId: EpisodeId, sizeBytes: Long) {
        db.withTransaction {
            if (db.localFileDao().markReplaced(episodeId.value, clock.now()) > 0) db.episodeDao().updateSize(episodeId.value, sizeBytes)
        }
    }

    override suspend fun abandonReplace(episodeId: EpisodeId) {
        db.localFileDao().abandonReplace(episodeId.value)
    }

    override suspend fun isReplaceStillWanted(episodeId: EpisodeId): Boolean {
        val row = db.localFileDao().findByEpisode(episodeId.value) ?: return false
        return row.state == DownloadState.DONE && row.replaceState != null
    }

    override suspend fun resetRunningReplacements() = db.localFileDao().resetRunningReplacements()

    companion object {
        const val PART_SUFFIX = ".part"
        private const val NO_PROGRAM = -1L
    }
}
