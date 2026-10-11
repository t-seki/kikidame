package dev.tseki.kikidame.data.sharedfolder

import org.junit.Test
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 位置を指定して読む関数を [java.io.InputStream] にする部品（#233）。 */
class PositionalInputStreamTest {
    private val bytes = ByteArray(10) { it.toByte() }
    private val positions = mutableListOf<Long>()

    private fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        positions += position
        if (position >= bytes.size) return -1
        val n = minOf(size, bytes.size - position.toInt())
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }

    private fun stream(
        offset: Long,
        readAt: (Long, ByteArray, Int, Int) -> Int = ::readAt,
        close: () -> Unit = {},
        mapError: (Exception) -> Exception = { it },
    ) = PositionalInputStream(offset, readAt, close, mapError)

    @Test
    fun readsFromTheOffsetAndAdvances() {
        val stream = stream(3, readAt = { p, b, o, s -> readAt(p, b, o, minOf(s, 4)) })
        assertContentEquals(byteArrayOf(3, 4, 5, 6, 7, 8, 9), stream.readBytes())
        assertEquals(listOf(3L, 7L, 10L), positions)
    }

    @Test
    fun singleByteReadsAndEnd() {
        val stream = stream(8)
        assertEquals(8, stream.read())
        assertEquals(9, stream.read())
        assertEquals(-1, stream.read())
    }

    @Test
    fun bytesAreUnsignedInSingleByteRead() {
        val stream = stream(0, readAt = { _, b, o, _ -> b[o] = 0xff.toByte(); 1 })
        assertEquals(0xff, stream.read())
    }

    @Test
    fun zeroLengthReadDoesNotCallReadAt() {
        assertEquals(0, stream(0).read(ByteArray(4), 0, 0))
        assertTrue(positions.isEmpty())
    }

    @Test
    fun zeroOrNegativeFromReadAtIsTheEnd() {
        assertEquals(-1, stream(0, readAt = { _, _, _, _ -> 0 }).read(ByteArray(4), 0, 4))
        assertEquals(-1, stream(0, readAt = { _, _, _, _ -> -1 }).read(ByteArray(4), 0, 4))
    }

    @Test
    fun readsIntoTheGivenOffsetOfTheBuffer() {
        val buffer = ByteArray(5)
        assertEquals(3, stream(7).read(buffer, 2, 3))
        assertContentEquals(byteArrayOf(0, 0, 7, 8, 9), buffer)
    }

    @Test
    fun closeCallsTheGivenClose() {
        var closed = 0
        stream(0, close = { closed++ }).close()
        assertEquals(1, closed)
    }

    @Test
    fun errorsFromReadAtGoThroughMapError() {
        val original = IllegalStateException("boom")
        val mapped = IOException("mapped", original)
        val received = mutableListOf<Exception>()
        val stream = stream(0, readAt = { _, _, _, _ -> throw original }, mapError = { received += it; mapped })
        assertSame(mapped, assertFailsWith<IOException> { stream.read(ByteArray(1), 0, 1) })
        assertEquals(listOf<Exception>(original), received)
    }

    @Test
    fun errorsAreNotMapped() {
        val stream = stream(0, readAt = { _, _, _, _ -> throw OutOfMemoryError("oom") }, mapError = { IOException(it) })
        assertFailsWith<OutOfMemoryError> { stream.read(ByteArray(1), 0, 1) }
    }

    @Test
    fun failedReadDoesNotAdvance() {
        var fail = true
        val stream = stream(2, readAt = { p, b, o, s -> if (fail) throw IllegalStateException() else readAt(p, b, o, s) })
        assertFailsWith<IllegalStateException> { stream.read(ByteArray(1), 0, 1) }
        fail = false
        assertEquals(2, stream.read())
    }
}
