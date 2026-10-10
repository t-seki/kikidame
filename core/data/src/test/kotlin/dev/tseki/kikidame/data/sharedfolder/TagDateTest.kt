package dev.tseki.kikidame.data.sharedfolder

import kotlinx.datetime.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** タグの日付は年月日まであるときだけ使う（#197、epic #195 の決定 9）。 */
class TagDateTest {
    @Test
    fun fullDatesAreRead() {
        val expected = LocalDate(2026, 9, 18)
        assertEquals(expected, TagDate.parse("2026-09-18"))
        assertEquals(expected, TagDate.parse("2026-09-18T01:00:00+09:00"))
        assertEquals(expected, TagDate.parse("2026/9/18"))
        assertEquals(expected, TagDate.parse("20260918"))
        assertEquals(expected, TagDate.parse("20260918T150000.000Z"))
        assertEquals(expected, TagDate.parse(" 2026-09-18 "))
    }

    @Test
    fun partialOrUnreadableDatesAreNull() {
        assertNull(TagDate.parse(null))
        assertNull(TagDate.parse(""))
        assertNull(TagDate.parse("2026"))
        assertNull(TagDate.parse("2026-09"))
        assertNull(TagDate.parse("2026-13-40"))
        assertNull(TagDate.parse("そのうち"))
        assertNull(TagDate.parse("19040101T000000.000Z"), "MP4 epoch means no date")
    }
}
