package dev.tseki.jellyfinradio.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.C
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import dev.tseki.jellyfinradio.domain.EpisodeId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 聴いている回の見た目に要る分。タイトル・番組名は [MediaItem] のメタデータから取り、Room は引かない。 */
data class NowPlayingState(
    val episodeId: EpisodeId,
    val title: String,
    val programName: String?,
    val isPlaying: Boolean,
    /**
     * 最後まで聴き終えて止まっている。キューの最後なら `STATE_ENDED`、途中の回でもスリープタイマーの
     * 「この回の終わりまで」で回の終わりで止めたとき（#36）。載ったままだが、同期はもう削除から外さない（#27）。
     */
    val isEnded: Boolean = false,
    /** ミニプレイヤーの再生位置の線（#59）。再生中は 1 秒ごとに更新する。尺が分からなければ [durationMs] は 0。 */
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/**
 * 聴いている回 = 今プレイヤーに載っている各回（止まっていても）。[PlaybackService] が書き、
 * 同期がこの回（聴き終えていなければ）を今回の削除から外し、一覧のマークとミニプレイヤーがこの回を指す（ADR 0006）。
 * 聴いている回が利用者の操作なしに消えたとき（再生の失敗）は、その理由も [messages] でここから UI へ渡す。
 * Worker・Service・UI は同一プロセスなので、MediaController を結ばずにここで受け渡す。
 */
@Singleton
class NowPlaying @Inject constructor() {
    private val _state = MutableStateFlow<NowPlayingState?>(null)
    val state: StateFlow<NowPlayingState?> = _state

    /** 同期が今回の削除から外す各回。載っていて、まだ聴き終えていない回。 */
    val excludedFromSync: EpisodeId?
        get() = _state.value?.takeIf { !it.isEnded }?.episodeId

    fun set(state: NowPlayingState?) {
        _state.value = state
    }

    // 出した時点で誰も見ていなくても（再生画面・バックグラウンド）、次に見た画面へ 1 回だけ届くように Channel で持つ
    private val _messages = Channel<String>(Channel.CONFLATED)

    /** 聴いている回が利用者の操作なしに消えた理由（再生の失敗など）。今見えている画面（再生画面か一覧）が 1 回だけ受け取って出す。 */
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun say(message: String) {
        _messages.trySend(message)
    }

    /**
     * [Player] に付けて現在の [MediaItem] と再生中かどうか、再生位置を追う。サービス終了時は [set] に null を渡す。
     * 位置は再生中だけ [tick] ごとに読む（[scope] は [player] のアプリケーションスレッドで動くこと。サービス破棄で cancel される）。
     */
    fun listener(player: Player, scope: CoroutineScope, tick: Duration = 1.seconds): Player.Listener = object : Player.Listener {
        /** 回の終わりで止めた（`pauseAtEndOfMediaItems`）。再開するか回が変われば下ろす。 */
        private var pausedAtEndOfItem = false
        private var ticker: Job? = null
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            pausedAtEndOfItem = false
            read(player, pausedAtEndOfItem)
        }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = read(player, pausedAtEndOfItem)
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            read(player, pausedAtEndOfItem)
            if (isPlaying) startTicker() else stopTicker()
        }
        override fun onPlaybackStateChanged(playbackState: Int) = read(player, pausedAtEndOfItem)
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            pausedAtEndOfItem = !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            read(player, pausedAtEndOfItem)
        }
        // シークは止まっていても位置が変わる。回をまたぐシークなら「回の終わりで止めた」も下ろす
        // （onMediaItemTransition より先に呼ばれるので、古い回の印を新しい回に一瞬付けない）
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) pausedAtEndOfItem = false
            read(player, pausedAtEndOfItem)
        }
        private fun startTicker() {
            if (ticker?.isActive == true) return
            ticker = scope.launch {
                while (isActive) {
                    delay(tick)
                    read(player, pausedAtEndOfItem)
                }
            }
        }
        private fun stopTicker() {
            ticker?.cancel()
            ticker = null
        }
    }
    private fun read(player: Player, pausedAtEndOfItem: Boolean) {
        val item = player.currentMediaItem
        val episodeId = EpisodeMediaItems.episodeId(item)
        set(
            if (item == null || episodeId == null) {
                null
            } else {
                NowPlayingState(
                    episodeId = episodeId,
                    title = item.mediaMetadata.title?.toString() ?: "",
                    programName = item.mediaMetadata.artist?.toString(),
                    isPlaying = player.isPlaying,
                    isEnded = player.playbackState == Player.STATE_ENDED || pausedAtEndOfItem,
                    positionMs = player.currentPosition.coerceAtLeast(0),
                    // PositionPersister.currentRuntime と同じ判定（0 も「分からない」）
                    durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: item.mediaMetadata.durationMs ?: 0,
                )
            },
        )
    }
}
