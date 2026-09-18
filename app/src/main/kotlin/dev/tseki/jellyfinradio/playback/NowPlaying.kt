package dev.tseki.jellyfinradio.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import dev.tseki.jellyfinradio.domain.EpisodeId
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 聴いている回の見た目に要る分。タイトル・番組名は [MediaItem] のメタデータから取り、Room は引かない。 */
data class NowPlayingState(
    val episodeId: EpisodeId,
    val title: String,
    val programName: String?,
    val isPlaying: Boolean,
    /** 最後まで聴き終えて止まっている（`STATE_ENDED`）。載ったままだが、同期はもう削除から外さない（#27）。 */
    val isEnded: Boolean = false,
)

/**
 * 聴いている回 = 今プレイヤーに載っている各回（止まっていても）。[PlaybackService] が書き、
 * 同期がこの回（聴き終えていなければ）を今回の削除から外し、一覧のマークとミニプレイヤーがこの回を指す（ADR 0006）。
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

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 聴いている回が利用者の操作なしに消えた理由（再生の失敗など）。一覧がスナックバーに出す。 */
    val messages: Flow<String> = _messages

    fun say(message: String) {
        _messages.tryEmit(message)
    }

    /** [Player] に付けて現在の [MediaItem] と再生中かどうかを追う。サービス終了時は [set] に null を渡す。 */
    fun listener(player: Player): Player.Listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = read(player)

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = read(player)

        override fun onIsPlayingChanged(isPlaying: Boolean) = read(player)

        override fun onPlaybackStateChanged(playbackState: Int) = read(player)
    }

    private fun read(player: Player) {
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
                    isEnded = player.playbackState == Player.STATE_ENDED,
                )
            },
        )
    }
}
