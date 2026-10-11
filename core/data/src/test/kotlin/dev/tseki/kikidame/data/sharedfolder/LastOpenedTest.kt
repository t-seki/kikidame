package dev.tseki.kikidame.data.sharedfolder

import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** 最後に開いたファイルを持ち回す部品（#233）。 */
class LastOpenedTest {
    private val events = mutableListOf<String>()

    private inner class Handle(val path: String, private val failOnClose: Boolean = false) : AutoCloseable {
        override fun close() {
            events += "close $path"
            if (failOnClose) throw IOException("close failed: $path")
        }
    }

    private fun open(path: String): Handle {
        events += "open $path"
        return Handle(path)
    }

    @Test
    fun samePathReusesTheHandle() {
        val last = LastOpened<Handle>()
        val first = last.get("a", ::open)
        assertSame(first, last.get("a", ::open))
        assertEquals(listOf("open a"), events)
    }

    @Test
    fun anotherPathOpensFirstThenClosesTheOld() {
        val last = LastOpened<Handle>()
        last.get("a", ::open)
        val b = last.get("b", ::open)
        assertEquals("b", b.path)
        assertEquals(listOf("open a", "open b", "close a"), events)
        assertSame(b, last.get("b", ::open))
        assertEquals(3, events.size)
    }

    @Test
    fun failingToOpenKeepsTheOld() {
        val last = LastOpened<Handle>()
        val a = last.get("a", ::open)
        assertFailsWith<IOException> { last.get("b") { throw IOException("cannot open: $it") } }
        assertEquals(listOf("open a"), events)
        assertSame(a, last.get("a", ::open))
        assertEquals(listOf("open a"), events)
    }

    @Test
    fun closeEmptiesAndTheNextGetOpensAgain() {
        val last = LastOpened<Handle>()
        last.get("a", ::open)
        last.close()
        last.close()
        last.get("a", ::open)
        assertEquals(listOf("open a", "close a", "open a"), events)
    }

    @Test
    fun failuresWhileClosingAreSwallowed() {
        val last = LastOpened<Handle>()
        last.get("a") { events += "open a"; Handle(it, failOnClose = true) }
        last.get("b", ::open)
        last.get("c") { events += "open c"; Handle(it, failOnClose = true) }
        last.close()
        assertEquals(listOf("open a", "open b", "close a", "open c", "close b", "close c"), events)
    }

    /** 開く途中で [LastOpened.close] が呼ばれても（SMB の木は張り直すときに自分を閉じる）、開いたものを持つ。 */
    @Test
    fun closingWhileOpeningKeepsTheNewHandle() {
        val last = LastOpened<Handle>()
        last.get("a", ::open)
        val b = last.get("b") {
            last.close()
            open(it)
        }
        assertEquals(listOf("open a", "close a", "open b"), events)
        assertSame(b, last.get("b", ::open))
    }

    @Test
    fun closeQuietlySwallowsExceptions() {
        closeQuietly { throw IOException("ignored") }
    }
}
