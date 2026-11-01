package dev.tseki.kikidame.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals

class PublishedAtTest {
    @Test
    fun `日付があればその日の JST 0 時`() {
        val expected = LocalDate(2026, 4, 1).atStartOfDayIn(PublishedAt.ZONE)
        assertEquals(expected, PublishedAt.of(LocalDate(2026, 4, 1), LocalDate(2026, 5, 2)))
    }

    @Test
    fun `日付が無ければ取り込み日の JST 0 時`() {
        val expected = LocalDate(2026, 5, 2).atStartOfDayIn(PublishedAt.ZONE)
        assertEquals(expected, PublishedAt.of(null, LocalDate(2026, 5, 2)))
    }
}
