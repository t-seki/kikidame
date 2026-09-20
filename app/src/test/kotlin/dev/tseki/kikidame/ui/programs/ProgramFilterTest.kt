package dev.tseki.kikidame.ui.programs

import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ProgramSummary
import dev.tseki.kikidame.ui.programs.ProgramFilter.Publisher
import dev.tseki.kikidame.ui.programs.ProgramFilter.PublisherKey
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProgramFilterTest {
    private val haraichi = summary(1, "ハライチのターン", "TBS")
    private val ann = summary(2, "オールナイトニッポン", "LFR")
    private val noPublisher = summary(3, "手元だけの番組 TBS 特集", null)
    private val all = listOf(haraichi, ann, noPublisher)

    @Test
    fun matchesPartOfProgramName() {
        assertEquals(listOf(haraichi), ProgramFilter.apply(all, "ターン"))
    }

    @Test
    fun matchesPartOfPublisherName() {
        // 配信元名の一致と、番組名に配信元名が含まれる場合の両方が残る
        assertEquals(listOf(haraichi, noPublisher), ProgramFilter.apply(all, "TBS"))
    }

    @Test
    fun ignoresCase() {
        assertEquals(listOf(haraichi, noPublisher), ProgramFilter.apply(all, "tbs"))
        assertEquals(listOf(ann), ProgramFilter.apply(all, "lfr"))
    }

    @Test
    fun nullPublisherIsJudgedByNameOnly() {
        // 「配信元なし」というラベル文字列で引っかからない。番組名では一致する
        assertEquals(emptyList(), ProgramFilter.apply(listOf(noPublisher), "配信元なし"))
        assertEquals(listOf(noPublisher), ProgramFilter.apply(listOf(noPublisher), "手元だけ"))
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

    // ---- 配信元の絞り込み（#45） ----
    private val tbs = PublisherKey("TBS")
    private val lfr = PublisherKey("LFR")
    private val none = PublisherKey(null)
    @Test
    fun filtersByPublisherExactly() {
        assertEquals(listOf(haraichi), ProgramFilter.apply(all, "", tbs))
        // 「TBS」を番組名に含む配信元なしの番組は、配信元の絞り込みでは残らない（完全一致）
        assertEquals(listOf(noPublisher), ProgramFilter.apply(all, "", none))
        assertSame(all, ProgramFilter.apply(all, "", null))
    }
    @Test
    fun publisherAndQueryAreAnded() {
        assertEquals(listOf(haraichi), ProgramFilter.apply(all, "ターン", tbs))
        assertEquals(emptyList(), ProgramFilter.apply(all, "ターン", lfr))
        assertEquals(emptyList(), ProgramFilter.apply(all, "ZZZ", tbs))
    }
    @Test
    fun publisherSelectionAloneIsActive() {
        assertTrue(ProgramFilter.isActive("", tbs))
        assertFalse(ProgramFilter.isActive("  ", null))
    }
    @Test
    fun publishersAreOrderedByCountThenNameWithNoneLast() {
        val list = listOf(
            summary(1, "a", "LFR"), summary(2, "b", "LFR"),
            summary(3, "c", "TBS"), summary(4, "d", "TBS"),
            summary(5, "e", "ABC"),
            // 配信元なしが一番多くても最後
            summary(6, "f", null), summary(7, "g", null), summary(8, "h", null),
        )
        assertEquals(
            listOf(Publisher(lfr, 2), Publisher(tbs, 2), Publisher(PublisherKey("ABC"), 1), Publisher(none, 3)),
            ProgramFilter.publishers(list),
        )
        assertEquals("配信元なし", none.label)
        assertEquals(emptyList(), ProgramFilter.publishers(emptyList()))
    }
    private fun summary(id: Long, name: String, publisher: String?) = ProgramSummary(
        program = Program(id = ProgramId(id), serverItemId = null, name = name, publisherName = publisher),
        episodeCount = 0,
        localEpisodeCount = 0,
        unplayedLocalCount = 0,
        latestPublishedAt = null,
    )
}
