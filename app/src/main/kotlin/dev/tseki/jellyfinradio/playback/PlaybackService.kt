package dev.tseki.jellyfinradio.playback
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.tseki.jellyfinradio.MainActivity
import dev.tseki.jellyfinradio.di.ApplicationScope
import dev.tseki.jellyfinradio.domain.AppSettingsRepository
import dev.tseki.jellyfinradio.domain.LibraryRepository
import dev.tseki.jellyfinradio.domain.PlaybackStateRepository
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
/** 通知・ロック画面を持つ再生サービス。再生専用（ADR 0003）。 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var playbackStateRepository: PlaybackStateRepository
    @Inject lateinit var clock: Clock
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope
    @Inject lateinit var nowPlaying: NowPlaying
    @Inject lateinit var settings: AppSettingsRepository
    @Inject lateinit var sleepTimer: SleepTimer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private var positionPersister: PositionPersister? = null
    private var resumeOnTransition: ResumeOnTransition? = null
    private var nowPlayingListener: Player.Listener? = null
    private var speedJob: Job? = null
    private var sleepTimerRunner: SleepTimerRunner? = null
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
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "ファイルが見つからないため再生を止めました"
                    else -> "再生に失敗したため止めました（${error.errorCodeName}）"
                },
            )
        }
    }
    override fun onCreate() {
        super.onCreate()
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
        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(sessionActivity)
            .setMediaButtonPreferences(
                listOf(
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
                        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                        .setDisplayName("10 秒戻る")
                        .setSlots(CommandButton.SLOT_BACK)
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
                        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                        .setDisplayName("10 秒進む")
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
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }
    override fun onDestroy() {
        // player に触るものは release() の前に止める
        speedJob?.cancel()
        sleepTimerRunner?.detach()
        positionPersister?.detach()
        resumeOnTransition?.detach()
        session?.run {
            nowPlayingListener?.let(player::removeListener)
            player.removeListener(errorListener)
            player.release()
            release()
        }
        nowPlaying.set(null)
        session = null
        scope.cancel()
        super.onDestroy()
    }
    /**
     * コントローラから来た [MediaItem] は `mediaId`（各回 ID）だけを信頼し、
     * URI とメタデータはこちらで引き直す。
     */
    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future {
            mediaItems.mapNotNull { item ->
                val episodeId = EpisodeMediaItems.episodeId(item) ?: return@mapNotNull null
                val episode = libraryRepository.getEpisode(episodeId)?.takeIf { it.isPlayable } ?: return@mapNotNull null
                val program = libraryRepository.observeProgram(episode.episode.programId).first()
                EpisodeMediaItems.toMediaItem(episode, program)
            }
        }
    }
    companion object {
        private const val TAG = "PlaybackService"
        const val SKIP_MS = 10_000L
    }
}
