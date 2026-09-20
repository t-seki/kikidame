package dev.tseki.kikidame.domain
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Instant
/** 番組一覧の 1 行。[LibraryRepository.observePrograms] は最新の各回の放送日順で返し、画面はよく聴く番組を先に分けて出す。 */
data class ProgramSummary(
    val program: Program,
    /** サーバ上の分も含めた各回の数。 */
    val episodeCount: Int,
    /** 手元にファイルがある各回の数。 */
    val localEpisodeCount: Int,
    /** 手元にファイルがあって再生済みでない各回の数（#41）。聴きかけ・聴いている回も再生済みでなければ数える。サーバ上にしか無い回は数えない。 */
    val unplayedLocalCount: Int,
    val latestAiredAt: Instant?,
)
/** 各回一覧・再生画面が使う、各回とその手元の状態。 */
data class EpisodeWithState(
    val episode: Episode,
    val localFile: LocalFile?,
    val playback: PlaybackState?,
) {
    val isPlayable: Boolean get() = localFile?.state == DownloadState.DONE && localFile.path != null
    /**
     * 次に開いたとき途中から再開する位置。先頭から始まる（未再生・末尾付近・記録なし）なら null。
     * 一覧の進捗表示はこれに従い、再生済みかどうかは見ない。
     */
    val resumePosition: Duration?
        get() = playback?.let { PlaybackRules.resumePosition(it.position, episode.runtime) }
            ?.takeIf { it > Duration.ZERO }
}
/** 手元のファイルの合計（#42）。手元にある（DONE でパスがある）各回だけで、ダウンロード途中の分は入らない。 */
data class LocalStorageUsage(val totalBytes: Long, val episodeCount: Int) {
    companion object {
        /** 一覧（番組の各回など）から数える。[LibraryRepository.observeLocalStorage] の全体の集計と同じ定義。 */
        fun of(episodes: List<EpisodeWithState>): LocalStorageUsage {
            val local = episodes.filter { it.isPlayable }
            return LocalStorageUsage(local.sumOf { it.episode.sizeBytes }, local.size)
        }
    }
}
interface LibraryRepository {
    /** 最新の各回の放送日が新しい順。 */
    fun observePrograms(): Flow<List<ProgramSummary>>
    /** 手元のファイルの合計。ダウンロード・削除で追従する。 */
    fun observeLocalStorage(): Flow<LocalStorageUsage>
    fun observeProgram(programId: ProgramId): Flow<Program?>
    /** [EpisodeOrder.newestFirst]（放送日の新しい順、同着はタイトルの辞書順）。 */
    fun observeEpisodes(programId: ProgramId): Flow<List<EpisodeWithState>>
    suspend fun getEpisode(episodeId: EpisodeId): EpisodeWithState?
    /** 連続再生用。[EpisodeOrder] の順（古い順）で、手元にあるものだけ。 */
    suspend fun getPlayableEpisodes(programId: ProgramId): List<EpisodeWithState>

    /** 同期対象と保持ルールを保存する。適用は次の同期（保存した瞬間には何も消えない）。 */
    suspend fun updateSync(programId: ProgramId, syncEnabled: Boolean, rule: RetentionRule)

    /** よく聴くの印を付ける／外す。表示にだけ効く。 */
    suspend fun setStarred(programId: ProgramId, starred: Boolean)
    /** 同期対象を OFF にしたら次の同期で消える回の数（固定でない手元のファイル）。確認ダイアログ用。 */
    suspend fun countUnpinnedLocalFiles(programId: ProgramId): Int
}
interface PlaybackStateRepository {
    suspend fun get(episodeId: EpisodeId): PlaybackState?
    fun observe(episodeId: EpisodeId): Flow<PlaybackState?>
    /**
     * 行が無ければ [PlaybackState.initial] から始めて [transform] を適用し保存する。
     * 読み取り・変換・書き込みは 1 トランザクション。ただし [PlaybackRules.isWorthRecording] が
     * false（行が無く、結果も聴き始めていない）なら書かずに結果だけ返す。
     */
    suspend fun update(episodeId: EpisodeId, transform: (PlaybackState) -> PlaybackState): PlaybackState
}
