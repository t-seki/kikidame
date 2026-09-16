package dev.tseki.jellyfinradio.ui.player
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.LibraryRepository
import dev.tseki.jellyfinradio.domain.PlaybackRules
import dev.tseki.jellyfinradio.domain.PlaybackStateRepository
import dev.tseki.jellyfinradio.playback.EpisodeMediaItems
import dev.tseki.jellyfinradio.playback.PlayerConnection
import dev.tseki.jellyfinradio.ui.PlayerRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Clock
data class PlayerUiState(
    val episodeId: EpisodeId? = null,
    val title: String = "",
    val programName: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val error: String? = null,
)
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val library: LibraryRepository,
    private val playbackStates: PlaybackStateRepository,
    private val connection: PlayerConnection,
    private val clock: Clock,
) : ViewModel() {
    private val requestedEpisodeId = EpisodeId(savedStateHandle.toRoute<PlayerRoute>().episodeId)
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState
    /** 再生中の回の再生済みフラグ。Room を正として表示する。 */
    val played: StateFlow<Boolean> = _uiState
        .map { it.episodeId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(false) else playbackStates.observe(id).map { it?.played == true } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private var controller: MediaController? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)
    }
    init {
        viewModelScope.launch {
            val c = connection.controller()
            controller = c
            c.addListener(listener)
            open(c, requestedEpisodeId)
            while (isActive) {
                refresh(c)
                delay(POSITION_REFRESH_MS)
            }
        }
    }
    /**
     * 指定の回を開く。同じ番組のキューが既に積まれていればその中でシークし、
     * 既にその回を再生中なら何もしない（画面に戻ってきただけ）。
     */
    private suspend fun open(player: Player, episodeId: EpisodeId) {
        if (EpisodeMediaItems.episodeId(player.currentMediaItem) == episodeId && player.playbackState != Player.STATE_IDLE) {
            player.play()
            return
        }
        val target = library.getEpisode(episodeId)
        if (target == null || !target.isPlayable) {
            _uiState.update { it.copy(error = "この回は手元にありません") }
            return
        }
        val program = library.observeProgram(target.episode.programId).first()
        val queue = library.getPlayableEpisodes(target.episode.programId)
        val index = queue.indexOfFirst { it.episode.id == episodeId }.coerceAtLeast(0)
        val saved = playbackStates.get(episodeId)
        val startMs = saved?.let { PlaybackRules.resumePosition(it.position, target.episode.runtime) }
            ?.inWholeMilliseconds ?: 0L
        val queueIds = queue.map { EpisodeMediaItems.mediaId(it.episode.id) }
        val currentIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (queueIds == currentIds) {
            player.seekTo(index, startMs)
        } else {
            val items: List<MediaItem> = queue.map { EpisodeMediaItems.toMediaItem(it, program) }
            player.setMediaItems(items, index, startMs)
        }
        player.prepare()
        player.play()
    }
    private fun refresh(player: Player) {
        val item = player.currentMediaItem
        _uiState.update {
            it.copy(
                episodeId = EpisodeMediaItems.episodeId(item),
                title = item?.mediaMetadata?.title?.toString() ?: "",
                programName = item?.mediaMetadata?.artist?.toString(),
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.takeIf { d -> d != C.TIME_UNSET }
                    ?: item?.mediaMetadata?.durationMs ?: 0,
                hasPrevious = player.hasPreviousMediaItem(),
                hasNext = player.hasNextMediaItem(),
            )
        }
    }
    fun togglePlayPause() = controller?.let { if (it.isPlaying) it.pause() else it.play() }
    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)
    fun seekBack() = controller?.seekBack()
    fun seekForward() = controller?.seekForward()
    fun previous() = controller?.seekToPreviousMediaItem()
    fun next() = controller?.seekToNextMediaItem()
    fun setPlayed(played: Boolean) {
        val id = _uiState.value.episodeId ?: return
        viewModelScope.launch {
            playbackStates.update(id) { PlaybackRules.setPlayed(it, played, clock.now()) }
        }
    }
    override fun onCleared() {
        controller?.removeListener(listener)
    }
    companion object {
        const val POSITION_REFRESH_MS = 500L
    }
}
