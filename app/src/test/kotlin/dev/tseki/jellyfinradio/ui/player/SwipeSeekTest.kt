package dev.tseki.jellyfinradio.ui.player

import org.junit.Test
import kotlin.test.assertEquals

class SwipeSeekTest {
    @Test
    fun tenDpIsOneSecond() {
        assertEquals(1_000L, SwipeSeek.deltaMs(10f))
        assertEquals(-2_500L, SwipeSeek.deltaMs(-25f))
        assertEquals(0L, SwipeSeek.deltaMs(0f))
    }

    @Test
    fun targetIsClampedToTheEpisode() {
        assertEquals(67_000L, SwipeSeek.targetMs(startMs = 60_000L, offsetDp = 70f, durationMs = 3_600_000L))
        assertEquals(0L, SwipeSeek.targetMs(startMs = 5_000L, offsetDp = -100f, durationMs = 3_600_000L))
        assertEquals(3_600_000L, SwipeSeek.targetMs(startMs = 3_595_000L, offsetDp = 100f, durationMs = 3_600_000L))
    }

    @Test
    fun deltaTextShowsSignAndWholeSeconds() {
        assertEquals("+7 秒", SwipeSeek.deltaText(7_400L))
        assertEquals("−12 秒", SwipeSeek.deltaText(-12_000L))
        assertEquals("±0 秒", SwipeSeek.deltaText(400L))
    }
}
