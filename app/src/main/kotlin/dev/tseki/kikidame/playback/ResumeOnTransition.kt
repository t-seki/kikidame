package dev.tseki.kikidame.playback
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.PlaybackStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
/**
 * 前後の回への移動・自動遷移で新しい回に入ったとき、保存位置（[PlaybackRules.resumePosition]）へシークする。
 * Media3 のプレイリストは先頭の回にしか開始位置を持てないので、遷移時に補う。
 * プレイリスト差し替え時（[Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED]）は
 * 呼び出し側が開始位置を渡しているので触らない。
 */
class ResumeOnTransition(
    private val player: Player,
    private val repository: PlaybackStateRepository,
    private val scope: CoroutineScope,
) : Player.Listener {
    fun attach() = player.addListener(this)
    fun detach() = player.removeListener(this)
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        val episodeId = EpisodeMediaItems.episodeId(mediaItem) ?: return
        val runtime = mediaItem?.mediaMetadata?.durationMs?.takeIf { it > 0 }?.milliseconds ?: return
        scope.launch {
            val saved = repository.get(episodeId) ?: return@launch
            val resume = PlaybackRules.resumePosition(saved.position, runtime)
            // 遷移後に別の回へ移っていたら何もしない
            if (EpisodeMediaItems.episodeId(player.currentMediaItem) != episodeId) return@launch
            if (resume.inWholeMilliseconds > 0) player.seekTo(resume.inWholeMilliseconds)
        }
    }
}
