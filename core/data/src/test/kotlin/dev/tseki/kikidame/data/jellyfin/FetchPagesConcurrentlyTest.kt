package dev.tseki.kikidame.data.jellyfin

import dev.tseki.kikidame.data.jellyfin.SdkJellyfinGateway.Companion.fetchPagesConcurrently
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 1 番組の同期の親の直下の取得の頁を、並べて取る（#69）。全頁を漏れも重なりもなく取り、順に取ったときと同じ並びにする。
 * 同時に投げる数は上限を超えない。
 */
class FetchPagesConcurrentlyTest {
    private fun pages(all: List<Int>, pageSize: Int, starts: MutableList<Int>, inFlight: AtomicInteger, peak: AtomicInteger): suspend (Int) -> SdkJellyfinGateway.ItemPage<Int> = { start ->
        starts += start
        val now = inFlight.incrementAndGet()
        peak.updateAndGet { maxOf(it, now) }
        // 後の頁ほど早く返す（並びが返った順にならないことを見る）
        delay((all.size - start).toLong())
        inFlight.decrementAndGet()
        SdkJellyfinGateway.ItemPage(all.drop(start).take(pageSize), all.size)
    }

    private fun check(total: Int, pageSize: Int, concurrency: Int) = runTest {
        val all = (0 until total).toList()
        val starts = Collections.synchronizedList(ArrayList<Int>())
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val got = fetchPagesConcurrently(concurrency, pages(all, pageSize, starts, inFlight, peak))
        assertEquals(all, got)
        val expectedStarts = if (total == 0) listOf(0) else (0 until total step pageSize).toList()
        assertEquals(expectedStarts, starts.sorted())
        assertTrue(peak.get() <= concurrency, "peak ${peak.get()} > $concurrency")
        // 最初の頁の後の残りの頁は、上限まで実際に並べて投げる（順に 1 本ずつ取る形に戻ると、peak が 1 になって落ちる）
        val rest = expectedStarts.size - 1
        if (rest > 0) assertEquals(minOf(concurrency, rest), peak.get(), "pages were not fetched concurrently")
    }

    @Test
    fun takesEveryPageInOrder() = check(total = 1897, pageSize = 500, concurrency = 4)

    @Test
    fun keepsTheLimitOnConcurrentPages() = check(total = 1897, pageSize = 100, concurrency = 3)

    @Test
    fun exactMultipleOfThePageSize() = check(total = 1000, pageSize = 500, concurrency = 4)

    /** 1 番組の同期の親の直下の取得は、頁を並べて投げる。上限は実機で 4 と 2 を比べて 4 にした（#69）。 */
    @Test
    fun theParentListingFetchesFourPagesAtOnce() = assertEquals(4, SdkJellyfinGateway.PAGE_CONCURRENCY)

    @Test
    fun singlePage()= check(total = 7, pageSize = 500, concurrency = 4)

    @Test
    fun empty() = check(total = 0, pageSize = 500, concurrency = 4)

    @Test
    fun takesTheRestInOrderWhenTheListGrowsWhileFetching() = runTest {
        val all = (0 until 1200).toList()
        var calls = 0
        val got = fetchPagesConcurrently(4) { start ->
            calls++
            // 最初の頁では 1000 件、後の頁では 1200 件と答える（取る間に増えた）
            val total = if (start == 0) 1000 else all.size
            SdkJellyfinGateway.ItemPage(all.drop(start).take(500), total)
        }
        assertEquals(all, got)
        assertEquals(3, calls)
    }
}
