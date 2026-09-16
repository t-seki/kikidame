package dev.tseki.jellyfinradio.playback
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.PlaybackRules
import dev.tseki.jellyfinradio.domain.PlaybackStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
/**
 * [Player] の位置を Room に書く。保存のタイミングは
 * 一時停止・停止（`isPlaying` が false になったとき）、回の切替（前の回の位置）、
 * 再生終了、再生中 [SAVE_INTERVAL] ごと。
 *
 * [playerScope] は [player] のアプリケーションスレッドで動くこと（Player はスレッド拘束）。
 * 書き込みは [persistScope] に載せる。サービス破棄で [playerScope] が cancel されても
 * 最後の保存が失われないよう、こちらはプロセス寿命のスコープを渡す。
 */
class PositionPersister(
    private val player: Player,
    private val repository: PlaybackStateRepository,
    private val clock: Clock,
    private val playerScope: CoroutineScope,
    private val persistScope: CoroutineScope = playerScope,
    private val saveInterval: Duration = SAVE_INTERVAL,
) : Player.Listener {
    private var ticker: Job? = null
    fun attach() {
        player.addListener(this)
        if (player.isPlaying) startTicker()
    }
    fun detach() {
        stopTicker()
        player.removeListener(this)
        saveCurrent()
    }
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            startTicker()
        } else {
            stopTicker()
            saveCurrent()
        }
    }
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        val oldEpisode = EpisodeMediaItems.episodeId(oldPosition.mediaItem) ?: return
        if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) return
        // 前の回の位置。再生し切って自動遷移したなら位置 = 尺として保存する
        val runtime = runtimeOf(oldPosition.mediaItem)
        val position = if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && runtime != null) {
            runtime
        } else {
            oldPosition.positionMs.milliseconds
        }
        save(oldEpisode, position, runtime)
    }
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            // 最後の回を再生し切った（自動遷移は起きない）
            val episode = EpisodeMediaItems.episodeId(player.currentMediaItem) ?: return
            val runtime = currentRuntime()
            save(episode, runtime ?: player.currentPosition.milliseconds, runtime)
        }
    }
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = playerScope.launch {
            while (isActive) {
                delay(saveInterval)
                saveCurrent()
            }
        }
    }
    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }
    private fun saveCurrent() {
        val episode = EpisodeMediaItems.episodeId(player.currentMediaItem) ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        save(episode, player.currentPosition.milliseconds, currentRuntime())
    }
    private fun save(episodeId: EpisodeId, position: Duration, runtime: Duration?) {
        persistScope.launch {
            repository.update(episodeId) { state ->
                if (runtime == null) {
                    // 尺が分からなければ再生済み判定はできない。位置だけ進める
                    state.copy(position = position, updatedAt = clock.now(), syncedAt = null)
                } else {
                    PlaybackRules.advance(state, position, runtime, clock.now())
                }
            }
        }
    }
    private fun currentRuntime(): Duration? {
        val duration = player.duration
        if (duration != C.TIME_UNSET && duration > 0) return duration.milliseconds
        return runtimeOf(player.currentMediaItem)
    }
    private fun runtimeOf(mediaItem: MediaItem?): Duration? =
        mediaItem?.mediaMetadata?.durationMs?.takeIf { it > 0 }?.milliseconds
    companion object {
        val SAVE_INTERVAL: Duration = 10.seconds
    }
}
