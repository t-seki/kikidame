package dev.tseki.kikidame.testing

import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.LocalDeletionScope
import dev.tseki.kikidame.domain.ProgramId
import kotlinx.coroutines.CompletableDeferred

/**
 * 何も持たない [DownloadRepository]。[reconcileMissingFiles] は [gate] を待ってから [missing] を返す。
 * [pin] は固定した回を [pinned] に残す。[deleteLocal] は消した回を [deleted] に残し、[deletionScope] を返す。[removeProgram] は消した番組を [removedPrograms] に残す。
 */
class FakeDownloadRepository(
    var missing: Int = 0,
    var gate: CompletableDeferred<Unit>? = null,
) : DownloadRepository {
    val pinned = mutableListOf<EpisodeId>()
    val deleted = mutableListOf<EpisodeId>()
    var deletionScope = LocalDeletionScope.FILE_ONLY
    val removedPrograms = mutableListOf<ProgramId>()
    override suspend fun enqueue(episodeId: EpisodeId) = Unit
    override suspend fun enqueueForSync(episodeIds: List<EpisodeId>) = episodeIds.size
    override suspend fun removeEpisode(episodeId: EpisodeId) = Unit
    override suspend fun removeProgram(programId: ProgramId) {
        removedPrograms += programId
    }
    override suspend fun cancel(episodeId: EpisodeId) = Unit
    override suspend fun retry(episodeId: EpisodeId) = Unit
    override suspend fun pin(episodeId: EpisodeId) {
        pinned += episodeId
    }
    override suspend fun unpin(episodeId: EpisodeId) = Unit
    override suspend fun deleteLocal(episodeId: EpisodeId): LocalDeletionScope {
        deleted += episodeId
        return deletionScope
    }
    override suspend fun reconcileMissingFiles(): Int {
        gate?.await()
        return missing
    }
    override suspend fun ensureFilePresent(episodeId: EpisodeId): Boolean = true
}
