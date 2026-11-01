package dev.tseki.kikidame.ui.player

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 位置を次に読むまでの待ち時間。 */
class PositionRefreshTest {
    private fun delayOf(playing: Boolean, pos: Long, speed: Float, next: Long? = null): Long =
        nextPositionRefreshDelayMs(playing, pos, speed, listOfNotNull(EpisodeChapter(0, "a"), next?.let { EpisodeChapter(it, "b") }))

    @Test fun playingAtOneX() = assertEquals(730, delayOf(true, 35_300, 1.0f))

    @Test fun exactlyOnSecondBoundary() = assertEquals(1030, delayOf(true, 35_000, 1.0f))

    @Test fun justBeforeBoundary() = assertEquals(31, delayOf(true, 35_999, 1.0f))

    @Test fun doubleSpeed() = assertEquals(380, delayOf(true, 35_300, 2.0f))

    @Test fun halfSpeed() = assertEquals(1430, delayOf(true, 35_300, 0.5f))

    @Test fun chapterSooner() = assertEquals(230, delayOf(true, 35_300, 1.0f, 35_500))

    @Test fun chapterSoonerAtDoubleSpeed() = assertEquals(130, delayOf(true, 35_300, 2.0f, 35_500))

    @Test fun secondBoundarySooner() = assertEquals(730, delayOf(true, 35_300, 1.0f, 36_800))

    @Test fun stoppedIsFixed() = assertEquals(500, delayOf(false, 35_300, 1.0f, 35_500))

    @Test fun zeroSpeedIsFixed() = assertEquals(500, delayOf(true, 35_300, 0f, 35_500))

    @Test fun negativeSpeedIsFixed() = assertEquals(500, delayOf(true, 35_300, -1f))

    @Test fun chapterAtPositionIsNotNext() = assertEquals(730, delayOf(true, 35_300, 1.0f, 35_300))

    @Test
    fun neverBelowMarginWhilePlaying() {
        for (pos in 0L..2_000L step 7) {
            for (speed in listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f)) {
                assertTrue(delayOf(true, pos, speed, pos + 1) >= POSITION_REFRESH_MARGIN_MS)
            }
        }
    }
}
