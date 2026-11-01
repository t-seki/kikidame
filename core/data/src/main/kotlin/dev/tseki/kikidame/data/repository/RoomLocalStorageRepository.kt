package dev.tseki.kikidame.data.repository

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.tseki.kikidame.data.db.LocalEpisodeRow
import dev.tseki.kikidame.data.db.LocalFileDao
import dev.tseki.kikidame.data.db.ProgramStorageRow
import dev.tseki.kikidame.domain.DeletedLocalEpisodes
import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeOrder
import dev.tseki.kikidame.domain.LocalEpisode
import dev.tseki.kikidame.domain.LocalStorageRepository
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ProgramStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 手元のファイルの内訳と、選んだ回の削除。消すのは 1 回ずつ [DownloadRepository.deleteLocal]（範囲は `deletionScopeFor`）。
 * 問い合わせは `playback_states` も見るので、再生中の再生位置の保存（10 秒ごと）でも再実行される。結果が同じなら下流に流さない。
 */
@Singleton
class RoomLocalStorageRepository @Inject constructor(
    private val localFileDao: LocalFileDao,
    private val downloads: DownloadRepository,
) : LocalStorageRepository {
    override fun observePrograms(): Flow<List<ProgramStorage>> =
        localFileDao.observeProgramStorage().map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    override fun observeEpisodes(): Flow<List<LocalEpisode>> =
        localFileDao.observeLocalEpisodes().map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    override fun observeEpisodes(programId: ProgramId): Flow<List<LocalEpisode>> =
        localFileDao.observeLocalEpisodesByProgram(programId.value)
            .map { rows -> rows.map { it.toDomain() }.sortedWith(NEWEST_FIRST) }
            .distinctUntilChanged()

    override suspend fun deleteLocalEpisodes(episodeIds: List<EpisodeId>, except: EpisodeId?): DeletedLocalEpisodes {
        // 選んだ後に消えた回（同期で消えた、ほかの画面で消した）と、ダウンロード中に戻った回は消さない（deleteLocal は取り消しも兼ねてしまう）
        val present = localFileDao.observeLocalEpisodes().first().map { EpisodeId(it.episodeId) }.toSet()
        val targets = episodeIds.distinct().filter { it in present }
        val removable = targets.filter { it != except }
        for (id in removable) downloads.deleteLocal(id)
        return DeletedLocalEpisodes(deleted = removable.size, kept = targets.size - removable.size)
    }

    private fun ProgramStorageRow.toDomain() = ProgramStorage(
        programId = ProgramId(programId),
        programName = programName,
        totalBytes = totalBytes,
        episodeCount = episodeCount,
        pinnedCount = pinnedCount,
        playedUnpinnedCount = playedUnpinnedCount,
        unplayedUnpinnedCount = unplayedUnpinnedCount,
    )

    private fun LocalEpisodeRow.toDomain() = LocalEpisode(
        episodeId = EpisodeId(episodeId),
        programId = ProgramId(programId),
        programName = programName,
        title = title,
        publishedAt = publishedAt,
        sizeBytes = sizeBytes,
        pinned = pinned,
        played = played,
        hasSourceItemId = hasSourceItemId,
        programSyncEnabled = programSyncEnabled,
    )

    private companion object {
        val NEWEST_FIRST: Comparator<LocalEpisode> = EpisodeOrder.newestFirstBy({ it.publishedAt }, { it.title }, { it.episodeId.value })
    }
}

/**
 * [RoomLocalStorageRepository] の束ね。ほかの束ねは `di/DataModule.kt` の `RepositoryModule` にあるが、
 * これは実装の隣に置いている。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LocalStorageModule {
    @Binds
    abstract fun bindLocalStorageRepository(impl: RoomLocalStorageRepository): LocalStorageRepository
}
