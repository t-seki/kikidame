package dev.tseki.kikidame.ui.player
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.AppSettingsRepository
import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.LibraryRepository
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.PlaybackSpeed
import dev.tseki.kikidame.domain.PlaybackStateRepository
import dev.tseki.kikidame.domain.ProgramImage
import dev.tseki.kikidame.playback.EpisodeMediaItems
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.playback.PlayerConnection
import dev.tseki.kikidame.playback.ProgramQueue
import dev.tseki.kikidame.playback.SleepTimer
import dev.tseki.kikidame.playback.SleepTimerSetting
import dev.tseki.kikidame.playback.knownRuntime
import dev.tseki.kikidame.ui.PlayerRoute
import dev.tseki.kikidame.text.UiText
import dev.tseki.kikidame.ui.toClockText
import dev.tseki.kikidame.ui.toPerformersText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    /** 再生中の回に埋め込まれたチャプター（#348。hidden を除き開始の順）。無ければ空。 */
    val chapters: List<EpisodeChapter> = emptyList(),
    val error: UiText? = null,
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
    private val settings: AppSettingsRepository,
    private val sleepTimer: SleepTimer,
    private val programQueue: ProgramQueue,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<PlayerRoute>()
    private val requestedEpisodeId = EpisodeId(route.episodeId)
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState
    private val _episodeImage = MutableStateFlow<ByteArray?>(null)
    /** 再生中の回の再生済みフラグ。Room を正として表示する。 */
    val played: StateFlow<Boolean> = _uiState
        .map { it.episodeId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(false) else playbackStates.observe(id).map { it?.played == true } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    /**
     * タイトルの下の `配信元 · 出演者`（#70）。配信元は番組から、出演者は各回から取り、どちらも Room を購読する
     * （この画面を開いたまま同期が出演者を書き換えても追従する）。どちらも無ければ null。番組 ID を知るために最初に 1 回だけ各回を引く。
     */
    val subtitle: StateFlow<UiText?> = _uiState
        .map { it.episodeId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) return@flatMapLatest flowOf(null)
            flow<UiText?> {
                val programId = library.getEpisode(id)?.episode?.programId ?: return@flow emit(null)
                val performers = library.observeEpisodes(programId).map { list -> list.firstOrNull { it.episode.id == id }?.episode?.performers.orEmpty() }
                emitAll(combine(library.observeProgram(programId), performers) { program, names -> playerSubtitle(program?.publisherName, names) })
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /**
     * 再生中の回の番組の画像（ADR 0012、#264）。無ければ null。番組を購読するので、この画面を開いたまま同期が画像を取り直しても追従する。
     * 画面に出すのは [episodeImage] を優先し、これはその回の画像が無いときの番組の画像。
     * 回の画像として番組の画像が載った後は、同期が番組の画像を取り直しても、次に回が替わるまで再生画面・通知の画像は差し替わらない（載せた `artworkData` を読むため）。
     */
    val programImage: StateFlow<ProgramImage?> = _uiState
        .map { it.episodeId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) return@flatMapLatest flowOf(null)
            flow<ProgramImage?> {
                val programId = library.getEpisode(id)?.episode?.programId ?: return@flow emit(null)
                emitAll(library.observeProgram(programId).map { it?.image })
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /**
     * 再生中の回の画像（#283）。サービスが現在の項目の `artworkData` に載せたもの（回の埋め込み画像、無ければ番組の画像。通知・ロック画面・Auto と同じ）。
     * 載るまで・どちらも無いときは null。
     */
    val episodeImage: StateFlow<ByteArray?> = _episodeImage

    /** 倍速（#35）。アプリ全体の設定で、サービス側が同じ Flow を購読してプレイヤーに反映する。 */
    val speed: StateFlow<Float> = settings.playbackSpeed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackSpeed.DEFAULT)

    fun setSpeed(speed: Float) {
        viewModelScope.launch {
            try {
                settings.setPlaybackSpeed(speed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = UiText.Res(R.string.common_error_save_settings, e::class.simpleName.orEmpty())) }
            }
        }
    }

    /**
     * スリープタイマーの表示（#36）: 残り時間「24:59」（60 分直後は「1:00:00」。一時停止中は止まったまま）、「回の終わり」、未設定なら null。
     * 設定済みかどうかは、画面がこれの null かどうかで見る（どの設定も最初の値をすぐ出すので、設定済みで null のまま残らない）。
     */
    val sleepTimerLabel: StateFlow<UiText?> = sleepTimer.setting
        .flatMapLatest { setting ->
            when (setting) {
                null -> flowOf(null)
                SleepTimerSetting.EndOfEpisode -> flowOf<UiText?>(UiText.Res(R.string.player_end_of_episode))
                is SleepTimerSetting.Countdown -> flow {
                    while (true) {
                        emit(UiText.Plain(setting.remainingAt(clock.now()).toClockText()))
                        delay(1_000)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** [after] 後に一時停止（再生中だけ減る）。null で解除。 */
    fun setSleepTimer(after: Duration?) = sleepTimer.set(after?.let { SleepTimerSetting.Countdown(it) })

    fun setSleepTimerToEndOfEpisode() = sleepTimer.set(SleepTimerSetting.EndOfEpisode)

    private var controller: MediaController? = null
    /** onEvents が位置の読みのループの待ちを打ち切って、その位置から待ち直させる（#367）。溜めるのは 1 つだけ。 */
    private val wakeUps = Channel<Unit>(Channel.CONFLATED)
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(player)
            wakeUps.trySend(Unit)
        }
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
            // 再生中は秒の境目（とチャプターの開始）の直後に読む（#367）。onEvents が来たらその位置から待ち直す
            while (isActive) {
                refresh(c)
                val wait = nextPositionRefreshDelayMs(c.isPlaying, c.currentPosition.coerceAtLeast(0), c.playbackParameters.speed, chapters)
                withTimeoutOrNull(wait) { wakeUps.receive() }
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
                val runtime = player.knownRuntime()
                if (runtime != null) {
                    val resume = PlaybackRules.resumePosition(player.currentPosition.milliseconds, runtime)
                    if (resume == Duration.ZERO) player.seekTo(0)
                }
                player.play()
            }
            return
        }
        val target = library.getEpisode(episodeId)
        if (target == null || !target.isPlayable) {
            _uiState.update { it.copy(error = UiText.Res(R.string.player_error_not_on_device)) }
            return
        }
        // ファイルマネージャ等で消されていたら、ここで整合して再生しない（#5）
        val present = runCatching { downloads.ensureFilePresent(episodeId) }.getOrDefault(true)
        if (!present) {
            _uiState.update { it.copy(error = UiText.Res(R.string.player_error_file_missing)) }
            return
        }
        // キューと開始位置は Auto・再開と共通（ProgramQueue）。今のキューと同じなら seekTo だけで済ます
        val (items, index, startMs) = programQueue.startingAt(target)
        val queueIds = items.map { it.mediaId }
        val currentIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (queueIds == currentIds) {
            player.seekTo(index, startMs)
        } else {
            player.setMediaItems(items, index, startMs)
        }
        player.prepare()
        if (play) player.play()
    }
    private var chaptersTracks: Tracks? = null
    private var chapters: List<EpisodeChapter> = emptyList()
    private var chaptersEpisodeId: EpisodeId? = null
    private fun refresh(player: Player) {
        // 回が替わったら前の回のチャプターを捨てる（新しい回の位置と組み合わさらないように。Tracks が追いつくまでは空）
        val episodeId = EpisodeMediaItems.episodeId(player.currentMediaItem)
        if (episodeId != chaptersEpisodeId) {
            chaptersEpisodeId = episodeId
            chaptersTracks = null
            chapters = emptyList()
        }
        // currentTracks は同じ値のうちは同じオブジェクトで返る見込み（未確認）。違っても結果は同じなので、変わったときだけ読み直す
        val tracks = player.currentTracks
        if (chaptersEpisodeId != null && tracks != chaptersTracks) {
            chaptersTracks = tracks
            chapters = chaptersOf(tracks)
        }
        val item = player.currentMediaItem
        val artwork = item?.mediaMetadata?.artworkData
        if (!(artwork contentEquals _episodeImage.value)) _episodeImage.value = artwork
        _uiState.update {
            it.copy(
                episodeId = EpisodeMediaItems.episodeId(item),
                title = item?.mediaMetadata?.title?.toString() ?: "",
                programName = item?.mediaMetadata?.artist?.toString(),
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.knownRuntime()?.inWholeMilliseconds ?: 0,
                hasPrevious = player.hasPreviousMediaItem(),
                hasNext = player.hasNextMediaItem(),
                chapters = chapters,
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
}
/** `配信元 · 出演者`。無い方は省き、両方無ければ null。出演者の区切りは言語で変わるので、解決は Composable に任せる。 */
internal fun playerSubtitle(publisherName: String?, performers: List<String>): UiText? =
    listOfNotNull(publisherName?.let(UiText::Plain), performers.toPerformersText()).takeIf { it.isNotEmpty() }?.let { UiText.Joined(it, UiText.Plain(" · ")) }
