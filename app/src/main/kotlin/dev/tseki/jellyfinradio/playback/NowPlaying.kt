package dev.tseki.jellyfinradio.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import dev.tseki.jellyfinradio.domain.EpisodeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 今プレイヤーに載っている各回（止まっていても）。[PlaybackService] が書き、同期がこの回を今回の削除から外す。
 * Worker と Service は同一プロセスなので、MediaController を結ばずにここで受け渡す。
 */
@Singleton
class NowPlaying @Inject constructor() {
    private val _current = MutableStateFlow<EpisodeId?>(null)
    val current: StateFlow<EpisodeId?> = _current

    fun set(episodeId: EpisodeId?) {
        _current.value = episodeId
    }

    /** [Player] に付けて現在の [MediaItem] を追う。サービス終了時は [set] に null を渡す。 */
    fun listener(player: Player): Player.Listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            set(EpisodeMediaItems.episodeId(mediaItem))
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            set(EpisodeMediaItems.episodeId(player.currentMediaItem))
        }
    }
}
