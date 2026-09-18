package dev.tseki.jellyfinradio.ui.programs

import dev.tseki.jellyfinradio.domain.Program
import dev.tseki.jellyfinradio.domain.ProgramId
import dev.tseki.jellyfinradio.domain.ProgramSummary
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProgramFilterTest {
    private val haraichi = summary(1, "ハライチのターン", "TBS")
    private val ann = summary(2, "オールナイトニッポン", "LFR")
    private val noStation = summary(3, "手元だけの番組 TBS 特集", null)
    private val all = listOf(haraichi, ann, noStation)

    @Test
    fun matchesPartOfProgramName() {
        assertEquals(listOf(haraichi), ProgramFilter.apply(all, "ターン"))
    }

    @Test
    fun matchesPartOfStationName() {
        // 局名の一致と、番組名に局名が含まれる場合の両方が残る
        assertEquals(listOf(haraichi, noStation), ProgramFilter.apply(all, "TBS"))
    }

    @Test
    fun ignoresCase() {
        assertEquals(listOf(haraichi, noStation), ProgramFilter.apply(all, "tbs"))
        assertEquals(listOf(ann), ProgramFilter.apply(all, "lfr"))
    }

    @Test
    fun nullStationIsJudgedByNameOnly() {
        // 「局なし」というラベル文字列で引っかからない。番組名では一致する
        assertEquals(emptyList(), ProgramFilter.apply(listOf(noStation), "局なし"))
        assertEquals(listOf(noStation), ProgramFilter.apply(listOf(noStation), "手元だけ"))
    }

    @Test
    fun trimsAndTreatsBlankAsNoFilter() {
        assertEquals(listOf(haraichi), ProgramFilter.apply(all, "  ターン "))
        assertSame(all, ProgramFilter.apply(all, ""))
        assertSame(all, ProgramFilter.apply(all, "   "))
        assertFalse(ProgramFilter.isActive("   "))
        assertTrue(ProgramFilter.isActive(" t "))
    }

    @Test
    fun doesNotSplitOnSpaces() {
        // 「TBS ターン」は AND ではなく文字列そのままの部分一致なので、どれにも一致しない
        assertEquals(emptyList(), ProgramFilter.apply(all, "TBS ターン"))
    }

    @Test
    fun keepsOrder() {
        assertEquals(listOf(haraichi, ann), ProgramFilter.apply(all, "ー"))
    }

    private fun summary(id: Long, name: String, station: String?) = ProgramSummary(
        program = Program(id = ProgramId(id), serverItemId = null, name = name, stationName = station),
        episodeCount = 0,
        localEpisodeCount = 0,
        latestAiredAt = null,
    )
}
