package dev.tseki.jellyfinradio.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PlaybackSpeedTest {
    @Test
    fun unknownOrMissingValueFallsBackToDefault() {
        assertEquals(1.0f, PlaybackSpeed.normalize(null))
        assertEquals(1.0f, PlaybackSpeed.normalize(3.0f))
        assertEquals(1.5f, PlaybackSpeed.normalize(1.5f))
    }

    @Test
    fun labelDropsTrailingZeroOnlyForWholeNumbers() {
        assertEquals("1×", PlaybackSpeed.label(1.0f))
        assertEquals("1.25×", PlaybackSpeed.label(1.25f))
        assertEquals("2×", PlaybackSpeed.label(2.0f))
    }
}
