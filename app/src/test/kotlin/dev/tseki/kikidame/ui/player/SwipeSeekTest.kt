package dev.tseki.kikidame.ui.player

import dev.tseki.kikidame.R
import dev.tseki.kikidame.ui.UiText
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
    fun onlyTheCenterAreaStartsASwipe() {
        val w = 1080f
        val h = 2000f
        assertEquals(true, SwipeSeek.isInCenterArea(x = 540f, y = 1000f, width = w, height = h))
        // 横は中央 60%（216..864）、縦は中央 70%（300..1700）
        assertEquals(true, SwipeSeek.isInCenterArea(x = 216f, y = 300f, width = w, height = h))
        assertEquals(false, SwipeSeek.isInCenterArea(x = 215f, y = 1000f, width = w, height = h))
        assertEquals(false, SwipeSeek.isInCenterArea(x = 900f, y = 1000f, width = w, height = h))
        assertEquals(false, SwipeSeek.isInCenterArea(x = 540f, y = 299f, width = w, height = h))
        assertEquals(false, SwipeSeek.isInCenterArea(x = 540f, y = 1701f, width = w, height = h))
    }
    @Test
    fun deltaTextShowsSignAndWholeSeconds() {
        assertEquals(UiText.Res(R.string.player_swipe_delta, "+7"), SwipeSeek.deltaText(7_400L))
        assertEquals(UiText.Res(R.string.player_swipe_delta, "−12"), SwipeSeek.deltaText(-12_000L))
        assertEquals(UiText.Res(R.string.player_swipe_delta, "±0"), SwipeSeek.deltaText(400L))
    }
}
