package dev.tseki.jellyfinradio.ui.player

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 再生画面のタイトル下 `放送局 · 出演者`（#70）。 */
class PlayerSubtitleTest {
    @Test
    fun joinsStationAndPerformers() {
        assertEquals("TBSラジオ · 岩井勇気、澤部佑", playerSubtitle("TBSラジオ", listOf("岩井勇気", "澤部佑")))
    }

    @Test
    fun omitsTheMissingPart() {
        assertEquals("TBSラジオ", playerSubtitle("TBSラジオ", emptyList()))
        assertEquals("岩井勇気", playerSubtitle(null, listOf("岩井勇気")))
    }

    @Test
    fun nullWhenBothAreMissing() {
        assertNull(playerSubtitle(null, emptyList()))
    }
}
