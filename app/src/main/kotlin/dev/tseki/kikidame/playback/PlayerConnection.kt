package dev.tseki.kikidame.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.guava.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PlaybackService] への [MediaController] をプロセス内で 1 つだけ持ち、利用者を数える。
 * [MediaController] はサービスに bind するので、誰かが持っている限り `stopSelf()` してもサービスは破棄されない。
 * 最後の利用者が [release] した時点で解放し、止まっているサービスが `onTaskRemoved` → `stopSelf()` で
 * 消えられるようにする（聴いている回もそこで無くなる）。メインスレッドから使う。
 *
 * サービスが先に死んで接続が切れたら、その controller は次の [acquire] で解放して作り直す。
 * 古い方をまだ持っている利用者の [release] は、自分の世代にだけ効く（新しい方の数を狂わせない）。
 */
@Singleton
class PlayerConnection @Inject constructor(@ApplicationContext private val context: Context) {
    /** 1 世代の接続とその利用者数。 */
    private class Slot(val future: ListenableFuture<MediaController>) {
        var holders = 0

        val controllerOrNull: MediaController?
            get() = if (future.isDone && !future.isCancelled) runCatching { future.get() }.getOrNull() else null

        /** 接続中、または接続が済んでまだ切れていない（サービスが止まると切れる）。 */
        val isAlive: Boolean get() = !future.isDone || controllerOrNull?.isConnected == true
    }

    private var slot: Slot? = null

    /** 利用者を 1 つ増やして controller を返す。使い終わったら必ずその controller で [release] する。 */
    suspend fun acquire(): MediaController {
        val current = slot?.takeIf { it.isAlive } ?: run {
            // サービスが止まって切断された古い controller は解放してから作り直す
            slot?.let { MediaController.releaseFuture(it.future) }
            Slot(
                MediaController.Builder(
                    context,
                    SessionToken(context, ComponentName(context, PlaybackService::class.java)),
                ).buildAsync(),
            ).also { slot = it }
        }
        current.holders++
        return try {
            // 待っている側が 1 つキャンセルされても、他の利用者のために接続は続ける
            Futures.nonCancellationPropagating(current.future).await()
        } catch (e: Throwable) {
            release(current)
            throw e
        }
    }

    /** [controller] の利用者を 1 つ減らし、最後の利用者が離れたら解放してサービスへの bind を外す。 */
    fun release(controller: MediaController) {
        val current = slot ?: return
        // 別の世代（既に解放済み）の controller なら何もしない
        if (current.controllerOrNull === controller) release(current)
    }

    private fun release(current: Slot) {
        current.holders = (current.holders - 1).coerceAtLeast(0)
        if (current.holders == 0) {
            MediaController.releaseFuture(current.future)
            if (slot === current) slot = null
        }
    }

    /**
     * 1 回の操作のために取得して、終わったら離す。ミニプレイヤーの操作のように、bind を持ち続けたくない場面用。
     * 呼んだ瞬間にサービスが死んでいれば bind で空のサービスが起動するが、離した時点で unbind されて消える。
     */
    suspend fun <T> use(block: suspend (MediaController) -> T): T {
        val controller = acquire()
        try {
            return block(controller)
        } finally {
            release(controller)
        }
    }
}
