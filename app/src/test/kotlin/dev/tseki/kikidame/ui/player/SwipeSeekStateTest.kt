package dev.tseki.kikidame.ui.player

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwipeSeekStateTest {
    private val seeks = mutableListOf<Long>()

    private fun state(positionMs: Long = 60_000L, durationMs: Long = 3_600_000L) = SwipeSeekState().apply {
        this.positionMs = positionMs
        this.durationMs = durationMs
        onSeek = { seeks += it }
    }

    // 1080x2000 の中央（中央の領域の中）と左端（外）
    private fun SwipeSeekState.startAtCenter() = start(x = 540f, y = 1000f, width = 1080f, height = 2000f)
    private fun SwipeSeekState.startAtEdge() = start(x = 10f, y = 1000f, width = 1080f, height = 2000f)

    @Test
    fun dragThenEndSeeksOnceFromThePositionAtStart() {
        val s = state()
        assertTrue(s.startAtCenter())
        assertEquals(60_000L, s.targetMs)
        // 開始後に再生位置が進んでも、基準は開始時の位置
        s.positionMs = 65_000L
        assertTrue(s.drag(30f))
        assertTrue(s.drag(40f))
        assertEquals(60_000L, s.startMs)
        assertEquals(74_000L, s.targetMs)
        assertEquals(emptyList(), seeks)
        s.end()
        assertEquals(listOf(74_000L), seeks)
        assertNull(s.targetMs)
        // 離した後のドラッグや 2 回目の end ではシークしない
        assertFalse(s.drag(10f))
        s.end()
        assertEquals(listOf(74_000L), seeks)
    }

    @Test
    fun targetIsClampedToTheEpisode() {
        val s = state(positionMs = 5_000L)
        s.startAtCenter()
        s.drag(-100f)
        assertEquals(0L, s.targetMs)
        s.end()
        assertEquals(listOf(0L), seeks)
    }

    @Test
    fun outsideTheCenterAreaDoesNotStart() {
        val s = state()
        assertFalse(s.startAtEdge())
        assertNull(s.targetMs)
        assertFalse(s.drag(50f))
        s.end()
        assertEquals(emptyList(), seeks)
    }

    @Test
    fun unknownDurationDoesNotStart() {
        val s = state(durationMs = 0L)
        assertFalse(s.startAtCenter())
        assertFalse(s.drag(50f))
        s.end()
        assertEquals(emptyList(), seeks)
    }

    @Test
    fun cancelDropsTheDragWithoutSeeking() {
        val s = state()
        s.startAtCenter()
        s.drag(50f)
        // ジェスチャの取り消し、または回の切り替え（rememberSwipeSeekState が cancel を呼ぶ）
        s.cancel()
        assertNull(s.targetMs)
        // 切り替えの後も指は動き続けるが、拾わない
        assertFalse(s.drag(50f))
        s.end()
        assertEquals(emptyList(), seeks)
    }

    @Test
    fun endCallsTheLatestOnSeek() {
        val s = state()
        s.startAtCenter()
        s.drag(10f)
        val latest = mutableListOf<Long>()
        s.onSeek = { latest += it }
        s.end()
        assertEquals(emptyList(), seeks)
        assertEquals(listOf(62_000L), latest)
    }
}
