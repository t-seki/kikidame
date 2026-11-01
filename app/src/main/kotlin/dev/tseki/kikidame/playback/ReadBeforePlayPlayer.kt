package dev.tseki.kikidame.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.PlaybackSharingRules
import dev.tseki.kikidame.domain.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * 再生の共有（ADR 0010 の決定 5）の「▶ の直前の読み込み」。セッションに渡すプレイヤーで、鳴らし始める（playWhenReady を立てる）前に
 * [refresh] で取得元の値を 1 回だけ読み、取得元の値を採ると決まったら、その位置（[PlaybackRules.resumePosition]）へ移ってから鳴らす。
 * 一時停止からの再開も同じ。画面・通知・Android Auto・ヘッドセットのボタンのどれから押しても、ここを通る。
 *
 * 待つのは [refresh] の打ち切り（[PlaybackSharingRules.READ_BEFORE_PLAY_TIMEOUT]）まで。失敗・打ち切りは手元の値で鳴らす。共有しない取得元では
 * [refresh] がすぐ null を返す。待っている間に一時停止が来たら、読み込みを捨てて鳴らさない。
 * 待っている間に出力先が外れた（ヘッドセットや Bluetooth が切れた。ACTION_AUDIO_BECOMING_NOISY）ときも、受ける側（PlaybackService）が
 * [cancelPendingPlay] を呼んで待っていた ▶ を取り消す。待っている間は中の ExoPlayer の「出力先が外れたら止める」（setHandleAudioBecomingNoisy）が
 * まだ効かず、そのままだと読み込みの後にスピーカーで鳴り始めるため。
 *
 * [scope] は [player] のアプリケーションスレッドで動くこと（Player はスレッド拘束）。
 */
// ForwardingSimpleBasePlayer は unstable API。クラス単位で opt-in する
@OptIn(UnstableApi::class)
class ReadBeforePlayPlayer(
    private val player: Player,
    private val scope: CoroutineScope,
    private val refresh: suspend (EpisodeId) -> PlaybackState?,
) : ForwardingSimpleBasePlayer(player) {
    /** 読んでいる途中の鳴らし始め。一時停止などの次の指示で捨てる。 */
    private var reading: ListenableFuture<*>? = null

    /**
     * 待っている ▶ があれば取り消して鳴らさない（画面・通知・セッションの状態は一時停止に戻る）。待っていなければ何もしない
     * （鳴っている最中の出力先の変化は、中の ExoPlayer に任せる）。[player] のアプリケーションスレッドで呼ぶこと。
     */
    fun cancelPendingPlay() {
        val pending = reading ?: return
        reading = null
        pending.cancel(true)
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        reading?.cancel(true)
        reading = null
        val episodeId = EpisodeMediaItems.episodeId(player.currentMediaItem)
        if (!playWhenReady || player.playWhenReady || episodeId == null) return super.handleSetPlayWhenReady(playWhenReady)
        val future = scope.future {
            val taken = try {
                // refresh は自分で打ち切るが、念のため上限を持つ（DB や接続の情報の読み込みも含めて待ちすぎない）
                withTimeoutOrNull(PlaybackSharingRules.READ_BEFORE_PLAY_TIMEOUT + 1.seconds) { refresh(episodeId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (taken != null && EpisodeMediaItems.episodeId(player.currentMediaItem) == episodeId) {
                val runtime = EpisodeMediaItems.runtimeOf(player.currentMediaItem)
                val resume = if (runtime != null) PlaybackRules.resumePosition(taken.position, runtime) else taken.position
                player.seekTo(resume.inWholeMilliseconds)
            }
            player.playWhenReady = true
        }
        reading = future
        return future
    }
}
