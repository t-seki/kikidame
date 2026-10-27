package dev.tseki.kikidame.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.tseki.kikidame.domain.EpisodeId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 再生の共有（ADR 0013 の決定 5、#355）の「送る」。未送信の回（[pending]）のうち、いま鳴っている回を除いたものを [send] に渡す。
 * これで一時停止・停止・回の終わり（鳴っている回でなくなった）と、再生済みの手動切替（鳴っていない回の印が立つ）の後に送られ、
 * 再生中の定期保存（鳴っている回）では送られない。
 *
 * 同じ顔ぶれ（[key] の一覧）では 1 回しか送らない。送れなかったときも、顔ぶれが変わるまでは送り直さない（次の同期が送る）。
 * 送ったかどうか・失敗の記録と、取得元の値を読んでから送ること・送信を直列にすることは、[send] の側（`PlaybackSharing.sendPending`）が持つ。
 * 動くのは再生サービスが生きている間だけ。サービスが無いときに切り替えた再生済みは、次の同期で送る。
 *
 * [scope] は [player] のアプリケーションスレッドで動くこと（Player はスレッド拘束）。[send] は [sendScope] で動かす
 * （サービスの破棄で [scope] が cancel されても、送り始めた分は送り切る）。
 */
class PlaybackSharePusher<T>(
    private val player: Player,
    private val scope: CoroutineScope,
    private val sendScope: CoroutineScope,
    private val pending: Flow<List<T>>,
    private val key: (T) -> Any,
    private val episodeOf: (T) -> EpisodeId,
    private val send: suspend (rows: List<T>, except: Set<EpisodeId>) -> Unit,
) : Player.Listener {
    private val playing = MutableStateFlow<EpisodeId?>(null)
    private var job: Job? = null

    fun attach() {
        player.addListener(this)
        updatePlaying()
        job = scope.launch {
            combine(pending, playing) { rows, now -> rows.filter { episodeOf(it) != now } to setOfNotNull(now) }
                .distinctUntilChanged { a, b -> a.first.map(key) == b.first.map(key) }
                .collectLatest { (rows, except) ->
                    if (rows.isEmpty()) return@collectLatest
                    sendScope.launch {
                        try {
                            send(rows, except)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // 送れなかった分は未送信のまま残り、次の同期で送る（失敗の記録は send の側）
                        }
                    }
                }
        }
    }

    fun detach() {
        job?.cancel()
        job = null
        player.removeListener(this)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlaying()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updatePlaying()

    private fun updatePlaying() {
        playing.value = if (player.isPlaying) EpisodeMediaItems.episodeId(player.currentMediaItem) else null
    }
}
