package dev.tseki.kikidame.ui.programs

import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ProgramSummary
import dev.tseki.kikidame.ui.programs.ProgramFilter.Station
import dev.tseki.kikidame.ui.programs.ProgramFilter.StationKey
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

    // ---- 放送局の絞り込み（#45） ----
    private val tbs = StationKey("TBS")
    private val lfr = StationKey("LFR")
    private val none = StationKey(null)
    @Test
    fun filtersByStationExactly() {
        assertEquals(listOf(haraichi), ProgramFilter.apply(all, "", tbs))
        // 「TBS」を番組名に含む局なしの番組は、局の絞り込みでは残らない（完全一致）
        assertEquals(listOf(noStation), ProgramFilter.apply(all, "", none))
        assertSame(all, ProgramFilter.apply(all, "", null))
    }
    @Test
    fun stationAndQueryAreAnded() {
        assertEquals(listOf(haraichi), ProgramFilter.apply(all, "ターン", tbs))
        assertEquals(emptyList(), ProgramFilter.apply(all, "ターン", lfr))
        assertEquals(emptyList(), ProgramFilter.apply(all, "ZZZ", tbs))
    }
    @Test
    fun stationSelectionAloneIsActive() {
        assertTrue(ProgramFilter.isActive("", tbs))
        assertFalse(ProgramFilter.isActive("  ", null))
    }
    @Test
    fun stationsAreOrderedByCountThenNameWithNoneLast() {
        val list = listOf(
            summary(1, "a", "LFR"), summary(2, "b", "LFR"),
            summary(3, "c", "TBS"), summary(4, "d", "TBS"),
            summary(5, "e", "ABC"),
            // 局なしが一番多くても最後
            summary(6, "f", null), summary(7, "g", null), summary(8, "h", null),
        )
        assertEquals(
            listOf(Station(lfr, 2), Station(tbs, 2), Station(StationKey("ABC"), 1), Station(none, 3)),
            ProgramFilter.stations(list),
        )
        assertEquals("局なし", none.label)
        assertEquals(emptyList(), ProgramFilter.stations(emptyList()))
    }
    private fun summary(id: Long, name: String, station: String?) = ProgramSummary(
        program = Program(id = ProgramId(id), serverItemId = null, name = name, stationName = station),
        episodeCount = 0,
        localEpisodeCount = 0,
        unplayedLocalCount = 0,
        latestAiredAt = null,
    )
}
