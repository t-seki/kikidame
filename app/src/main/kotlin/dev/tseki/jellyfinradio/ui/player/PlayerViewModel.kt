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
import dev.tseki.jellyfinradio.domain.DownloadRepository
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.LibraryRepository
import dev.tseki.jellyfinradio.domain.PlaybackRules
import dev.tseki.jellyfinradio.domain.PlaybackStateRepository
import dev.tseki.jellyfinradio.playback.EpisodeMediaItems
import dev.tseki.jellyfinradio.playback.NowPlaying
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
    private val downloads: DownloadRepository,
    nowPlaying: NowPlaying,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<PlayerRoute>()
    private val requestedEpisodeId = EpisodeId(route.episodeId)
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
        // 再生の失敗でキューが空になった理由。この画面が開いている間はここが受け取り、閉じていれば一覧のスナックバーが受け取る
        viewModelScope.launch {
            nowPlaying.messages.collect { message -> _uiState.update { it.copy(error = message) } }
        }
        viewModelScope.launch {
            val c = connection.acquire()
            controller = c
            c.addListener(listener)
            open(c, requestedEpisodeId, route.play)
            while (isActive) {
                refresh(c)
                delay(POSITION_REFRESH_MS)
            }
        }
    }
    /**
     * 指定の回を開く。同じ番組のキューが既に積まれていればその中でシークする。
     * 既にその回を再生中なら何もしない（画面に戻ってきただけ）。その回で止まっている
     * （一時停止・再生終了）なら、再生開始として再開位置の規則を適用してから再生する。
     * [play] が false（ミニプレイヤーから「見に行く」だけ）なら再生を始めない: その回が載っていれば
     * 止まっていても触らず、載っていなければ（プロセス死からの復元でサービスが死んでいたとき）積むだけにする。
     */
    private suspend fun open(player: Player, episodeId: EpisodeId, play: Boolean) {
        if (EpisodeMediaItems.episodeId(player.currentMediaItem) == episodeId && player.playbackState != Player.STATE_IDLE) {
            if (play && !player.isPlaying) {
                val runtime = player.duration.takeIf { it != C.TIME_UNSET }
                    ?: player.currentMediaItem?.mediaMetadata?.durationMs
                if (runtime != null && runtime > 0) {
                    val resume = PlaybackRules.resumePosition(player.currentPosition.milliseconds, runtime.milliseconds)
                    if (resume == Duration.ZERO) player.seekTo(0)
                }
                player.play()
            }
            return
        }
        val target = library.getEpisode(episodeId)
        if (target == null || !target.isPlayable) {
            _uiState.update { it.copy(error = "この回は手元にありません") }
            return
        }
        // ファイルマネージャ等で消されていたら、ここで整合して再生しない（#5）
        val present = runCatching { downloads.ensureFilePresent(episodeId) }.getOrDefault(true)
        if (!present) {
            _uiState.update { it.copy(error = "ファイルが見つかりません。手元の記録を整理しました") }
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
        if (play) player.play()
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
        controller?.let {
            it.removeListener(listener)
            connection.release(it)
        }
    }
    companion object {
        const val POSITION_REFRESH_MS = 500L
    }
}
