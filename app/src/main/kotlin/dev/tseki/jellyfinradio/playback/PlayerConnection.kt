package dev.tseki.jellyfinradio.playback
import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
/** [PlaybackService] への [MediaController] をプロセス内で 1 つだけ持つ。 */
@Singleton
class PlayerConnection @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    private var controller: MediaController? = null
    suspend fun controller(): MediaController = mutex.withLock {
        controller?.takeIf { it.isConnected } ?: run {
            // サービスが止まって切断された古いコントローラは解放してから作り直す
            controller?.release()
            controller = null
            MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            ).buildAsync().await().also { controller = it }
        }
    }
}
