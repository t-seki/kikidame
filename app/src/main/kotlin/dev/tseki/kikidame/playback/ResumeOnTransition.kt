package dev.tseki.kikidame.playback
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.PlaybackState
import dev.tseki.kikidame.domain.PlaybackStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
/**
 * 前後の回への移動・自動遷移で新しい回に入ったとき、保存位置（[PlaybackRules.resumePosition]）へシークする。
 * Media3 のプレイリストは先頭の回にしか開始位置を持てないので、遷移時に補う。
 * プレイリスト差し替え時（[Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED]）は
 * 呼び出し側が開始位置を渡しているので触らない。
 *
 * 再生の共有（ADR 0013 の決定 5、#355）: 新しい回に入ったら、手元の保存位置へ移った後に [refresh] で取得元の値を 1 回だけ読み、
 * 取得元の値を採ると決まったらその位置へ移り直す（回の再生を始める直前の読み込み。鳴り始めは止めず、読めた後に移る）。
 * 共有しない取得元では [refresh] がすぐ null を返す。
 */
class ResumeOnTransition(
    private val player: Player,
    private val repository: PlaybackStateRepository,
    private val scope: CoroutineScope,
    private val refresh: suspend (EpisodeId) -> PlaybackState? = { null },
) : Player.Listener {
    fun attach() = player.addListener(this)
    fun detach() = player.removeListener(this)
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        val episodeId = EpisodeMediaItems.episodeId(mediaItem) ?: return
        val runtime = EpisodeMediaItems.runtimeOf(mediaItem) ?: return
        scope.launch {
            repository.get(episodeId)?.let { saved -> seekTo(episodeId, saved.position, runtime, evenToStart = false) }
            val taken = runCatching { refresh(episodeId) }.getOrNull() ?: return@launch
            // 取得元の値を採ったら、先頭（ほかで聴き終えた・先頭に戻した）でも移る（手元の位置へ移った後なので）
            seekTo(episodeId, taken.position, runtime, evenToStart = true)
        }
    }

    /**
     * [episodeId] の [position] の規則（[PlaybackRules.resumePosition]）の再開位置へ移る。遷移後に別の回へ移っていたら何もしない。
     * [evenToStart] が false なら先頭（0）へは移らない（遷移した直後は先頭にいる）。
     */
    private fun seekTo(episodeId: EpisodeId, position: kotlin.time.Duration, runtime: kotlin.time.Duration, evenToStart: Boolean) {
        if (EpisodeMediaItems.episodeId(player.currentMediaItem) != episodeId) return
        val resume = PlaybackRules.resumePosition(position, runtime)
        if (evenToStart || resume.inWholeMilliseconds > 0) player.seekTo(resume.inWholeMilliseconds)
    }
}
