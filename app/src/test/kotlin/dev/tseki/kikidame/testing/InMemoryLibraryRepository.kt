package dev.tseki.kikidame.testing
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
/**
 * 番組と各回を持つだけの [LibraryRepository]。書き込みは同期の設定（[updateSync]）だけを [programs] に写し、呼ばれた記録を残す。
 * 手元の固定していないファイルの数（[countUnpinnedLocalFiles]）は数えずに [unpinnedLocalFiles] を返す。
 */
class InMemoryLibraryRepository : LibraryRepository {
    /** [observePrograms] の順（呼び出し側が最新回の公開日順に並べておく）。 */
    val programs = MutableStateFlow<List<Program>>(emptyList())
    val episodes = MutableStateFlow<List<EpisodeWithState>>(emptyList())
    override fun observePrograms(): Flow<List<ProgramSummary>> = programs.map { list ->
        list.map { program ->
            val own = episodes.value.filter { it.episode.programId == program.id }
            ProgramSummary(
                program = program,
                episodeCount = own.size,
                localEpisodeCount = own.count { it.isPlayable },
                unplayedLocalCount = own.count { it.isPlayable && it.playback?.played != true },
                latestPublishedAt = own.maxOfOrNull { it.episode.publishedAt },
            )
        }
    }
    override fun observeLocalStorage(): Flow<LocalStorageUsage> = episodes.map { LocalStorageUsage.of(it) }
    override fun observeProgram(programId: ProgramId): Flow<Program?> = programs.map { list -> list.firstOrNull { it.id == programId } }
    override fun observeEpisodes(programId: ProgramId): Flow<List<EpisodeWithState>> =
        episodes.map { list -> list.filter { it.episode.programId == programId }.sortedWith(compareBy(EpisodeOrder.newestFirst) { it.episode }) }
    override suspend fun getEpisode(episodeId: EpisodeId): EpisodeWithState? = episodes.value.firstOrNull { it.episode.id == episodeId }
    override suspend fun getPlayableEpisodes(programId: ProgramId): List<EpisodeWithState> =
        episodes.value.filter { it.episode.programId == programId && it.isPlayable }.sortedWith(compareBy(EpisodeOrder) { it.episode })
    override suspend fun getRecentlyListened(limit: Int): List<EpisodeWithState> = recentlyListened(episodes.value).take(limit)
    override fun observeRecentlyListened(): Flow<List<EpisodeWithState>> = episodes.map(::recentlyListened)
    private fun recentlyListened(list: List<EpisodeWithState>): List<EpisodeWithState> =
        list
            .filter { it.isPlayable && it.playback?.let { p -> !p.played && !PlaybackRules.isNotStarted(p) } == true }
            .sortedByDescending { it.playback!!.updatedAt }
    /** [updateSync] に渡されたもの（呼ばれた順）。 */
    val syncUpdates = mutableListOf<SyncUpdate>()
    data class SyncUpdate(val programId: ProgramId, val syncEnabled: Boolean, val rule: RetentionRule)
    var unpinnedLocalFiles = 0
    override suspend fun updateSync(programId: ProgramId, syncEnabled: Boolean, rule: RetentionRule) {
        syncUpdates += SyncUpdate(programId, syncEnabled, rule)
        programs.value = programs.value.map { if (it.id == programId) it.copy(syncEnabled = syncEnabled, retentionRule = rule) else it }
    }
    override suspend fun setStarred(programId: ProgramId, starred: Boolean) = error("not used")
    override suspend fun countUnpinnedLocalFiles(programId: ProgramId): Int = unpinnedLocalFiles
}
