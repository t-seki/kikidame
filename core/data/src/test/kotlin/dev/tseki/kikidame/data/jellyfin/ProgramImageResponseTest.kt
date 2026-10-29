package dev.tseki.kikidame.data.jellyfin

import dev.tseki.kikidame.domain.SourceException
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 番組の画像の応答の扱い: 上限の判定と、応答コードの対応。テスト用サーバは依存に無いので、読む部分を関数に括り出して見る。 */
class ProgramImageResponseTest {
    private val max = 8L

    /** 読んだ量を数える。上限を超えたら、そこで読むのをやめることを見る。 */
    private class CountingStream(size: Int) : InputStream() {
        var read = 0L
        private val total = size
        override fun read(): Int = if (read < total) { read++; 1 } else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read >= total) return -1
            val n = minOf(len.toLong(), total - read).toInt()
            read += n
            return n
        }
    }

    @Test
    fun contentLengthOverTheLimitIsNullWithoutReading() {
        val body = CountingStream(100)
        assertNull(SdkJellyfinGateway.readLimited(body, contentLength = 100, max = max))
        assertEquals(0L, body.read)
    }

    /** Content-Length が無い（-1）まま読みながら超えたら、切れたバイト列ではなく null。 */
    @Test
    fun unknownLengthThatGrowsPastTheLimitIsNull() {
        assertNull(SdkJellyfinGateway.readLimited(ByteArrayInputStream(ByteArray(20)), contentLength = -1, max = max))
    }

    @Test
    fun bodyWithinTheLimitIsReturnedWhole() {
        val bytes = ByteArray(8) { it.toByte() }
        assertContentEquals(bytes, SdkJellyfinGateway.readLimited(ByteArrayInputStream(bytes), contentLength = 8, max = max))
        assertContentEquals(bytes, SdkJellyfinGateway.readLimited(ByteArrayInputStream(bytes), contentLength = -1, max = max))
        assertContentEquals(ByteArray(0), SdkJellyfinGateway.readLimited(ByteArrayInputStream(ByteArray(0)), contentLength = 0, max = max))
    }

    @Test
    fun statusCodesMapToBodyNullOrErrors() {
        assertTrue(SdkJellyfinGateway.imageStatusHasBody(200))
        assertFalse(SdkJellyfinGateway.imageStatusHasBody(404))
        assertFailsWith<SourceException.Unauthorized> { SdkJellyfinGateway.imageStatusHasBody(401) }
        assertFailsWith<SourceException.Unauthorized> { SdkJellyfinGateway.imageStatusHasBody(403) }
        assertFailsWith<SourceException.Failed> { SdkJellyfinGateway.imageStatusHasBody(500) }
    }
}
