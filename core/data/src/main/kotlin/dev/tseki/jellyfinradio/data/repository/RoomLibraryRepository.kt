package dev.tseki.jellyfinradio.data.repository
import dev.tseki.jellyfinradio.data.db.EpisodeDao
import dev.tseki.jellyfinradio.data.db.ProgramDao
import dev.tseki.jellyfinradio.data.db.toDomain
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.EpisodeOrder
import dev.tseki.jellyfinradio.domain.EpisodeWithState
import dev.tseki.jellyfinradio.domain.LibraryRepository
import dev.tseki.jellyfinradio.domain.Program
import dev.tseki.jellyfinradio.domain.ProgramId
import dev.tseki.jellyfinradio.domain.ProgramSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class RoomLibraryRepository @Inject constructor(
    private val programDao: ProgramDao,
    private val episodeDao: EpisodeDao,
) : LibraryRepository {
    override fun observePrograms(): Flow<List<ProgramSummary>> =
        programDao.observeSummaries().map { rows -> rows.map { it.toDomain() } }
    override fun observeProgram(programId: ProgramId): Flow<Program?> =
        programDao.observeById(programId.value).map { it?.toDomain() }
    override fun observeEpisodes(programId: ProgramId): Flow<List<EpisodeWithState>> =
        episodeDao.observeByProgram(programId.value).map { rows ->
            rows.map { it.toDomain() }.sortedWith(compareByDescending(EpisodeOrder) { it.episode })
        }
    override suspend fun getEpisode(episodeId: EpisodeId): EpisodeWithState? =
        episodeDao.findById(episodeId.value)?.toDomain()
    override suspend fun getPlayableEpisodes(programId: ProgramId): List<EpisodeWithState> =
        episodeDao.listByProgram(programId.value)
            .map { it.toDomain() }
            .filter { it.isPlayable }
            .sortedWith(compareBy(EpisodeOrder) { it.episode })
}
