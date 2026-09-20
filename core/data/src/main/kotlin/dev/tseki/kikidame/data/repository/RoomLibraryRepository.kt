package dev.tseki.kikidame.data.repository
import dev.tseki.kikidame.data.db.EpisodeDao
import dev.tseki.kikidame.data.db.LocalFileDao
import dev.tseki.kikidame.data.db.ProgramDao
import dev.tseki.kikidame.data.db.toDomain
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeOrder
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LibraryRepository
import dev.tseki.kikidame.domain.LocalStorageUsage
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ProgramSummary
import dev.tseki.kikidame.domain.RetentionRule
import dev.tseki.kikidame.domain.Ticks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class RoomLibraryRepository @Inject constructor(
    private val programDao: ProgramDao,
    private val episodeDao: EpisodeDao,
    private val localFileDao: LocalFileDao,
) : LibraryRepository {
    // 集計は playback_states も見るので、再生中の再生位置の保存（10 秒ごと）でも再実行される。結果が同じなら下流に流さない
    override fun observePrograms(): Flow<List<ProgramSummary>> =
        programDao.observeSummaries().map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()
    override fun observeLocalStorage(): Flow<LocalStorageUsage> =
        localFileDao.observeUsage().map { LocalStorageUsage(it.totalBytes, it.episodeCount) }
    override fun observeProgram(programId: ProgramId): Flow<Program?> =
        programDao.observeById(programId.value).map { it?.toDomain() }
    override fun observeEpisodes(programId: ProgramId): Flow<List<EpisodeWithState>> =
        episodeDao.observeByProgram(programId.value).map { rows ->
            rows.map { it.toDomain() }.sortedWith(compareBy(EpisodeOrder.newestFirst) { it.episode })
        }
    override suspend fun getEpisode(episodeId: EpisodeId): EpisodeWithState? =
        episodeDao.findById(episodeId.value)?.toDomain()
    override suspend fun getPlayableEpisodes(programId: ProgramId): List<EpisodeWithState> =
        episodeDao.listByProgram(programId.value)
            .map { it.toDomain() }
            .filter { it.isPlayable }
            .sortedWith(compareBy(EpisodeOrder) { it.episode })
    // 「聴き始めている」の閾値は PlaybackRules から取り、SQL にバインドして LIMIT の前に絞る（Kotlin 側で絞り直すと
    // 上位が未開始の回で埋まったとき limit 件に満たなくなる）
    override suspend fun getRecentlyListened(limit: Int): List<EpisodeWithState> =
        episodeDao.listRecentlyListened(limit, Ticks.fromDuration(PlaybackRules.NOT_STARTED_THRESHOLD))
            .map { it.toDomain() }
    override suspend fun updateSync(programId: ProgramId, syncEnabled: Boolean, rule: RetentionRule) =
        programDao.updateSync(programId.value, syncEnabled, rule.keepLatest, rule.deleteAfterPlayed)
    override suspend fun setStarred(programId: ProgramId, starred: Boolean) =
        programDao.setStarred(programId.value, starred)
    override suspend fun countUnpinnedLocalFiles(programId: ProgramId): Int =
        localFileDao.countUnpinnedByProgram(programId.value)
}
