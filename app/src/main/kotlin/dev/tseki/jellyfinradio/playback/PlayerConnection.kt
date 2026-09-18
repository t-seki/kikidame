package dev.tseki.jellyfinradio.playback

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
 */
@Singleton
class PlayerConnection @Inject constructor(@ApplicationContext private val context: Context) {
    private var future: ListenableFuture<MediaController>? = null
    private var holders = 0

    /** 利用者を 1 つ増やして controller を返す。使い終わったら必ず [release] する。 */
    suspend fun acquire(): MediaController {
        val f = future?.takeIf { it.isAlive() } ?: MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)),
        ).buildAsync().also { future = it }
        holders++
        return try {
            // 待っている側が 1 つキャンセルされても、他の利用者のために接続は続ける
            Futures.nonCancellationPropagating(f).await()
        } catch (e: Throwable) {
            release()
            throw e
        }
    }

    /** 最後の利用者が離れたら controller を解放し、サービスへの bind を外す。 */
    fun release() {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) {
            future?.let(MediaController::releaseFuture)
            future = null
        }
    }

    /** 1 回の操作のために取得して、終わったら離す。 */
    suspend fun <T> use(block: suspend (MediaController) -> T): T {
        val controller = acquire()
        try {
            return block(controller)
        } finally {
            release()
        }
    }

    /** 接続中、または接続が済んでまだ切れていない（サービスが止まると切れる）。 */
    private fun ListenableFuture<MediaController>.isAlive(): Boolean =
        !isDone || (!isCancelled && runCatching { get().isConnected }.getOrDefault(false))
}
