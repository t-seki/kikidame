package dev.tseki.jellyfinradio.domain
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
/** 番組一覧の 1 行。最新の各回の放送日で並べる。 */
data class ProgramSummary(
    val program: Program,
    val episodeCount: Int,
    val latestAiredAt: Instant?,
)
/** 各回一覧・再生画面が使う、各回とその手元の状態。 */
data class EpisodeWithState(
    val episode: Episode,
    val localFile: LocalFile?,
    val playback: PlaybackState?,
) {
    val isPlayable: Boolean get() = localFile?.state == DownloadState.DONE && localFile.path != null
}
interface LibraryRepository {
    /** 最新の各回の放送日が新しい順。 */
    fun observePrograms(): Flow<List<ProgramSummary>>
    fun observeProgram(programId: ProgramId): Flow<Program?>
    /** [EpisodeOrder] の逆順（新しい順）。 */
    fun observeEpisodes(programId: ProgramId): Flow<List<EpisodeWithState>>
    suspend fun getEpisode(episodeId: EpisodeId): EpisodeWithState?
    /** 連続再生用。[EpisodeOrder] の順（古い順）で、手元にあるものだけ。 */
    suspend fun getPlayableEpisodes(programId: ProgramId): List<EpisodeWithState>
}
interface PlaybackStateRepository {
    suspend fun get(episodeId: EpisodeId): PlaybackState?
    fun observe(episodeId: EpisodeId): Flow<PlaybackState?>
    /**
     * 行が無ければ [PlaybackState.initial] から始めて [transform] を適用し保存する。
     * 読み取り・変換・書き込みは 1 トランザクション。
     */
    suspend fun update(episodeId: EpisodeId, transform: (PlaybackState) -> PlaybackState): PlaybackState
}
