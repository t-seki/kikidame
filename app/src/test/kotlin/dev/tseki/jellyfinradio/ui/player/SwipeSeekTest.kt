package dev.tseki.jellyfinradio.ui.player

import org.junit.Test
import kotlin.test.assertEquals

class SwipeSeekTest {
    @Test
    fun tenDpIsTwoSeconds() {
        assertEquals(2_000L, SwipeSeek.deltaMs(10f))
        assertEquals(-5_000L, SwipeSeek.deltaMs(-25f))
        assertEquals(0L, SwipeSeek.deltaMs(0f))
    }

    @Test
    fun targetIsClampedToTheEpisode() {
        assertEquals(74_000L, SwipeSeek.targetMs(startMs = 60_000L, offsetDp = 70f, durationMs = 3_600_000L))
        assertEquals(0L, SwipeSeek.targetMs(startMs = 5_000L, offsetDp = -100f, durationMs = 3_600_000L))
        assertEquals(3_600_000L, SwipeSeek.targetMs(startMs = 3_595_000L, offsetDp = 100f, durationMs = 3_600_000L))
    }

    @Test
    fun onlyTheCenterBandStartsASwipe() {
        assertEquals(true, SwipeSeek.isInCenterBand(x = 540f, width = 1080f))
        assertEquals(true, SwipeSeek.isInCenterBand(x = 216f, width = 1080f))
        assertEquals(false, SwipeSeek.isInCenterBand(x = 215f, width = 1080f))
        assertEquals(false, SwipeSeek.isInCenterBand(x = 900f, width = 1080f))
    }
    @Test
    fun deltaTextShowsSignAndWholeSeconds() {
        assertEquals("+7 秒", SwipeSeek.deltaText(7_400L))
        assertEquals("−12 秒", SwipeSeek.deltaText(-12_000L))
        assertEquals("±0 秒", SwipeSeek.deltaText(400L))
    }
}
