package dev.tseki.kikidame.playback
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.tseki.kikidame.MainActivity
import dev.tseki.kikidame.R
import dev.tseki.kikidame.ui.UiText
import dev.tseki.kikidame.di.ApplicationScope
import dev.tseki.kikidame.domain.AppSettingsRepository
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LibraryRepository
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.PlaybackStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.future
import javax.inject.Inject
import kotlin.time.Clock
/**
 * 通知・ロック画面を持つ再生サービス。再生専用（ADR 0003）。
 * Android Auto（#96）には [MediaLibraryService] として [BrowseTree] を出す。Auto は車載機からアプリの外で bind するので、
 * 画面を開かずにこのサービスが起動する経路がある（ADR 0006 の addendum）。
 */
// Media3 の MediaLibrarySession / LibraryResult は unstable API。クラス単位で opt-in する（#111）
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var playbackStateRepository: PlaybackStateRepository
    @Inject lateinit var clock: Clock
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope
    @Inject lateinit var nowPlaying: NowPlaying
    @Inject lateinit var settings: AppSettingsRepository
    @Inject lateinit var sleepTimer: SleepTimer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null
    private var positionPersister: PositionPersister? = null
    private var resumeOnTransition: ResumeOnTransition? = null
    private var nowPlayingListener: Player.Listener? = null
    private var speedJob: Job? = null
    private var sleepTimerRunner: SleepTimerRunner? = null
    private lateinit var browseTree: BrowseTree
    /**
     * 再生に失敗したら（原因を問わず）キューを空にし、理由を UI へ渡す。空にすれば聴いている回も無くなり、
     * ミニプレイヤーと通知が消える。典型は、聴き終えて止まっている回を同期が消した（#27）後に ▶ を押してファイルが無い場合。
     */
    private val errorListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback failed, clearing the queue", error)
            session?.player?.clearMediaItems()
            nowPlaying.say(
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> UiText.Res(R.string.playback_error_file_not_found)
                    else -> UiText.Res(R.string.playback_error_failed, error.errorCodeName)
                },
            )
        }
    }
    override fun onCreate() {
        super.onCreate()
        browseTree = BrowseTree(
            libraryRepository,
            BrowseTree.Labels(
                continueListening = getString(R.string.auto_root_continue),
                starred = getString(R.string.auto_root_starred),
                programs = getString(R.string.auto_root_programs),
            ),
        )
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SKIP_MS)
            .setSeekForwardIncrementMs(SKIP_MS)
            .build()
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibrarySessionCallback())
            .setSessionActivity(sessionActivity)
            .setMediaButtonPreferences(
                listOf(
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
                        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                        .setDisplayName(getString(R.string.playback_skip_back))
                        .setSlots(CommandButton.SLOT_BACK)
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
                        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                        .setDisplayName(getString(R.string.playback_skip_forward))
                        .setSlots(CommandButton.SLOT_FORWARD)
                        .build(),
                ),
            )
            .build()
        positionPersister = PositionPersister(player, playbackStateRepository, clock, scope, applicationScope)
            .also { it.attach() }
        resumeOnTransition = ResumeOnTransition(player, playbackStateRepository, scope).also { it.attach() }
        nowPlayingListener = nowPlaying.listener(player, scope).also(player::addListener)
        player.addListener(errorListener)
        // 倍速（#35）: アプリ全体で 1 つ。設定が変われば即反映。ピッチは変えない
        speedJob = scope.launch { settings.playbackSpeed.collect { speed -> player.setPlaybackSpeed(speed) } }
        // スリープタイマー（#36）: 画面を閉じても動くようここに住む
        sleepTimerRunner = SleepTimerRunner(player, sleepTimer, clock, scope) { player.pauseAtEndOfMediaItems = it }
            .also { it.attach() }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }
    override fun onDestroy() {
        // player に触るものは release() の前に止める。scope の cancel もここ（NowPlaying の位置の ticker が scope に住む）
        speedJob?.cancel()
        sleepTimerRunner?.detach()
        positionPersister?.detach()
        resumeOnTransition?.detach()
        scope.cancel()
        session?.run {
            nowPlayingListener?.let(player::removeListener)
            player.removeListener(errorListener)
            player.release()
            release()
        }
        nowPlaying.set(null)
        session = null
        super.onDestroy()
    }
    /**
     * コントローラから来た [MediaItem] は `mediaId`（各回 ID）だけを信頼し、
     * URI とメタデータはこちらで引き直す。ブラウズ（Auto）は [BrowseTree] に委ねる。
     */
    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future { resolve(mediaItems) }
        /**
         * Auto で各回を選ぶと、その 1 件だけが `setMediaItems` で来る。アプリの再生画面（`PlayerViewModel.open`）と同じく、
         * 番組の手元にある回を古い順に全部積み、その回から、保存位置の規則（[PlaybackRules.resumePosition]）で始める。
         * それ以外（複数件・開始位置の指定あり）は来たものをそのまま解決する。
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val single = mediaItems.singleOrNull()?.takeIf { startIndex == C.INDEX_UNSET && startPositionMs == C.TIME_UNSET }
            val episodeId = EpisodeMediaItems.episodeId(single)
            if (single == null || episodeId == null) {
                return@future MediaSession.MediaItemsWithStartPosition(resolve(mediaItems), startIndex, startPositionMs)
            }
            val target = libraryRepository.getEpisode(episodeId)?.takeIf { it.isPlayable }
                ?: return@future MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
            programQueueStartingAt(target)
        }
        /**
         * 再開（#108）: プレイヤーが空のとき（プロセス死後の通知の ▶、Auto の「最近」、端末の再起動後）に
         * 最後に聴いていた回を復元する。最近聴いた各回（[dev.tseki.kikidame.domain.LibraryRepository.getRecentlyListened]）の
         * 先頭から、[onSetMediaItems] と同じ番組のキューを組む。無ければ空のリスト（Media3 の既定と同じ扱い）。
         * 「聴いている回」（ADR 0006）はこの結果がプレイヤーに載った時点で立つ。
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val recent = libraryRepository.getRecentlyListened(1).firstOrNull()
                ?: return@future MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
            programQueueStartingAt(recent)
        }
        /**
         * [target] の番組の手元にある回を古い順に全部積み、その回から、保存位置の規則（[PlaybackRules.resumePosition]）で始める
         * （`PlayerViewModel.open` と同じ）。
         */
        private suspend fun programQueueStartingAt(target: EpisodeWithState): MediaSession.MediaItemsWithStartPosition {
            val episodeId = target.episode.id
            val program = libraryRepository.observeProgram(target.episode.programId).first()
            val queue = libraryRepository.getPlayableEpisodes(target.episode.programId)
            val index = queue.indexOfFirst { it.episode.id == episodeId }.coerceAtLeast(0)
            val saved = playbackStateRepository.get(episodeId)
            val startMs = saved?.let { PlaybackRules.resumePosition(it.position, target.episode.runtime) }
                ?.inWholeMilliseconds ?: 0L
            return MediaSession.MediaItemsWithStartPosition(queue.map { EpisodeMediaItems.toMediaItem(it, program) }, index, startMs)
        }
        private suspend fun resolve(mediaItems: List<MediaItem>): List<MediaItem> = mediaItems.mapNotNull { item ->
            val episodeId = EpisodeMediaItems.episodeId(item) ?: return@mapNotNull null
            val episode = libraryRepository.getEpisode(episodeId)?.takeIf { it.isPlayable } ?: return@mapNotNull null
            val program = libraryRepository.observeProgram(episode.episode.programId).first()
            EpisodeMediaItems.toMediaItem(episode, program)
        }
        /** ルート。「最近」（`isRecent`。端末起動後にシステムが出す再開の候補）は [BrowseTree.recentRoot]（#108）。 */
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            // 「最近」（isRecent）は再開候補 1 件だけのルート（#108）。それ以外は通常のツリー
            if (params?.isRecent == true) LibraryResult.ofItem(browseTree.recentRoot(), params)
            else LibraryResult.ofItem(browseTree.root(), params)
        }
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val children = browseTree.children(parentId)
                ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            LibraryResult.ofItemList(paginate(children, page, pageSize), params)
        }
        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val item = browseTree.item(mediaId) ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            LibraryResult.ofItem(item, null)
        }
        /** 購読は受け付けるだけ。ライブラリの変化を Auto に押し通知するのは今はしない（開き直せば最新）。 */
        override fun onSubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val children = browseTree.children(parentId)
                ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            session.notifyChildrenChanged(browser, parentId, children.size, params)
            LibraryResult.ofVoid()
        }
    }
    companion object {
        private const val TAG = "PlaybackService"
        const val SKIP_MS = 10_000L
        /** Auto は普通ページ分割せずに全部を求める。ページ指定が来たときだけ切る。 */
        internal fun paginate(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
            if (pageSize <= 0 || page < 0) return items
            val from = page.toLong() * pageSize
            if (from >= items.size) return emptyList()
            return items.subList(from.toInt(), minOf(items.size.toLong(), from + pageSize).toInt())
        }
    }
}
