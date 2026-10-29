package dev.tseki.kikidame.playback
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import kotlin.math.max
/**
 * 再生中の回の画像（ADR 0009、#283）。各回のファイルに埋め込まれた画像を、再生するときに手元のファイルから読む。
 * 埋め込みが無い（読めない）ときは番組の画像のファイル。どちらも無ければ null。
 * 通知・ロック画面・Auto には `setArtworkData` でバイト列を渡す（外のプロセスに画像を渡す ContentProvider は持たない）ので、
 * 縮めて（長辺 [MAX_SIDE_PX] まで）圧縮し直す。
 */
class EpisodeImage @Inject constructor(private val library: LibraryRepository) {
    suspend fun load(episodeId: EpisodeId): ByteArray? {
        val episode = library.getEpisode(episodeId) ?: return null
        val audioPath = episode.localFile?.path
        val programImagePath = library.getProgram(episode.episode.programId)?.image?.path
        return withContext(Dispatchers.IO) {
            audioPath?.let { candidate { embeddedPicture(it)?.let(::shrink) } }
                ?: programImagePath?.let { candidate { shrink(File(it).readBytes()) } }
        }
    }

    /** 候補 1 つの読み込み・縮小。失敗（OOM・不正な画像など）は、その候補が無いものとして null（次の候補へ）。[CancellationException] は投げ直す。 */
    private inline fun candidate(block: () -> ByteArray?): ByteArray? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun embeddedPicture(path: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            return retriever.embeddedPicture
        } finally {
            retriever.release()
        }
    }

    /** 読めない画像は null（次の候補へ）。透過のある画像は PNG のまま、それ以外は JPEG にする。 */
    private fun shrink(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = Integer.highestOneBit(max(1, max(bounds.outWidth, bounds.outHeight) / MAX_SIDE_PX))
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val longSide = max(decoded.width, decoded.height)
        val scaled = if (longSide > MAX_SIDE_PX) {
            val ratio = MAX_SIDE_PX.toFloat() / longSide
            Bitmap.createScaledBitmap(decoded, max(1, (decoded.width * ratio).toInt()), max(1, (decoded.height * ratio).toInt()), true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        val ok = if (scaled.hasAlpha()) scaled.compress(Bitmap.CompressFormat.PNG, 100, out) else scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return if (ok) out.toByteArray() else null
    }

    companion object {
        /** 通知・ロック画面・Auto・再生画面に足りる大きさ。値に根拠は無い（実機で見て決めたものではない。未確認）。 */
        const val MAX_SIDE_PX = 512
        private const val JPEG_QUALITY = 85
    }
}
