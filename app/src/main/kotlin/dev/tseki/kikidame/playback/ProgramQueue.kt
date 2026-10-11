package dev.tseki.kikidame.playback
import androidx.media3.common.MediaItem
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LibraryRepository
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.PlaybackStateRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
/**
 * 番組のキュー（#231）: ある回を聴き始めるとき、その番組の手元にある回を古い順に全部積み、その回から、
 * 保存位置の規則（[PlaybackRules.resumePosition]）で始める。
 * 再生画面（`PlayerViewModel.open`）と、サービスの Auto での選択（`onSetMediaItems`）・再開（`onPlaybackResumption`、#108）が使う。
 * プレイヤーには触らない（今のキューと比べて `seekTo` だけで済ますかの判定は呼び出し側に残す）。
 */
class ProgramQueue @Inject constructor(
    private val library: LibraryRepository,
    private val playbackStates: PlaybackStateRepository,
) {
    /** [items] の [index] 番目（[target]）を [startMs] から再生する。 */
    data class QueueStart(val items: List<MediaItem>, val index: Int, val startMs: Long)

    /** [target] は手元にある回（[EpisodeWithState.isPlayable]）。キューに見つからなければ先頭（0）から。 */
    suspend fun startingAt(target: EpisodeWithState): QueueStart {
        val episodeId = target.episode.id
        val program = library.observeProgram(target.episode.programId).first()
        val queue = library.getPlayableEpisodes(target.episode.programId)
        val index = queue.indexOfFirst { it.episode.id == episodeId }.coerceAtLeast(0)
        val saved = playbackStates.get(episodeId)
        val startMs = saved?.let { PlaybackRules.resumePosition(it.position, target.episode.runtime) }
            ?.inWholeMilliseconds ?: 0L
        return QueueStart(queue.map { EpisodeMediaItems.toMediaItem(it, program) }, index, startMs)
    }
}
