package dev.tseki.kikidame.data.sharedfolder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 取り消された走査は、共有の残りを読み続けない（#198。取得元を変えた後も古い共有全体の走査が走り続け、
 * 新しい取得元の同期を妨げた不具合）。木の操作は blocking でコルーチンの取り消しに気づかないので、走査が自分で見る。
 */
class SharedFolderCancellationTest {
    private val folder = MemoryFolderTree()

    /** 1 本目のタグの読み取りを、止まらない I/O の代わりに遅くする（[cancelled] が立つまで）。 */
    private class SlowTagReader : TagReader {
        val reads = AtomicInteger()
        val started = CompletableDeferred<Unit>()

        @Volatile
        var cancelled = false

        override fun read(tree: FolderTree, path: String, sizeBytes: Long): AudioTags {
            reads.incrementAndGet()
            started.complete(Unit)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!cancelled && System.nanoTime() < deadline) Thread.sleep(5)
            return AudioTags()
        }
    }

    @Test
    fun cancelledFullScanStopsBetweenFilesInsteadOfReadingTheWholeShare() = runBlocking {
        for (i in 1..50) folder.put("Pub/Program/ep$i.m4a")
        val tags = SlowTagReader()
        val source = SharedFolderSource(folder.tree, tags, ScannedFiles { emptyMap() }, Dispatchers.IO)

        val scan = async(Dispatchers.Default) { source.fetchAll() }
        tags.started.await()
        scan.cancel()
        tags.cancelled = true // 読み取り中の 1 本が終わる
        assertFailsWith<CancellationException> { scan.await() }

        assertEquals(1, tags.reads.get(), "the scan stops after the file being read, not after all 50")
    }

    @Test
    fun cancelledScanStopsBetweenFolders() = runBlocking {
        for (i in 1..30) folder.put("Pub$i/Program/ep.m4a")
        val tags = SlowTagReader()
        val source = SharedFolderSource(folder.tree, tags, ScannedFiles { emptyMap() }, Dispatchers.IO)

        val scan = async(Dispatchers.Default) { source.fetchAll() }
        tags.started.await()
        scan.cancel()
        tags.cancelled = true
        assertFailsWith<CancellationException> { scan.await() }

        assertTrue(tags.reads.get() <= 2, "reads=${tags.reads.get()}")
    }
}
