package dev.tseki.jellyfinradio.seed
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
data class AudioMetadata(val dateTag: String?, val duration: Duration?)
/** タグの読み取り。Android 依存はここに閉じ込め、放送日の決定は `AiredAt` に渡す。 */
interface AudioMetadataReader {
    fun read(file: File): AudioMetadata
}
class MediaMetadataRetrieverReader : AudioMetadataReader {
    override fun read(file: File): AudioMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            AudioMetadata(
                dateTag = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it > 0 }?.milliseconds,
            )
        } catch (e: RuntimeException) {
            // 壊れたファイルは取り込まないのではなく、放送日・尺のフォールバックに任せる
            AudioMetadata(dateTag = null, duration = null)
        } finally {
            retriever.release()
        }
    }
}
