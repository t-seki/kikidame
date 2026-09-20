package dev.tseki.kikidame.playback
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.robolectric.Shadows.shadowOf
/**
 * テスト用のフェイク [Player]。状態を直接書き換え、Media3 の [SimpleBasePlayer] に
 * リスナー通知を任せる（実際の ExoPlayer と同じイベント順序になる）。
 */
class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var state: State = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .build()
    override fun getState(): State = state
    init {
        // SimpleBasePlayer は最初の invalidateState() でその時点の状態を「変更前」として取り込む。
        // 空の状態で確定させておかないと、リスナーを付けてから最初に積んだプレイリストの通知が出ない
        invalidateState()
    }
    /** 状態を変更して通知を流し切る。 */
    fun update(block: State.Builder.() -> Unit) {
        state = state.buildUpon().apply(block).build()
        invalidateState()
        shadowOf(Looper.getMainLooper()).idle()
    }
    fun setPlaylist(items: List<MediaItem>, durationsMs: List<Long>) = update {
        setPlaylist(
            items.mapIndexed { i, item ->
                MediaItemData.Builder(item.mediaId).setMediaItem(item).setDurationUs(durationsMs[i] * 1000).build()
            },
        )
    }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        state = state.buildUpon()
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
        return Futures.immediateVoidFuture()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        state = state.buildUpon()
            .setCurrentMediaItemIndex(mediaItemIndex)
            .setContentPositionMs(positionMs)
            .setPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK, positionMs)
            .build()
        return Futures.immediateVoidFuture()
    }
    override fun handlePrepare(): ListenableFuture<*> {
        state = state.buildUpon().setPlaybackState(Player.STATE_READY).build()
        return Futures.immediateVoidFuture()
    }
    override fun handleStop(): ListenableFuture<*> {
        state = state.buildUpon().setPlaybackState(Player.STATE_IDLE).build()
        return Futures.immediateVoidFuture()
    }
    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
