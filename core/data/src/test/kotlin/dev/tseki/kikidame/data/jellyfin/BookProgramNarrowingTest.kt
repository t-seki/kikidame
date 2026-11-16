package dev.tseki.kikidame.data.jellyfin

import dev.tseki.kikidame.domain.SourceItemId
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals

/** ブックのライブラリの 1 番組の同期の安全網（ADR 0016）。実サーバ無しで、全件に切り替える 3 つの場合と、絞り込みで良い場合を見る。 */
class BookProgramNarrowingTest {
    private val programFolder = UUID.randomUUID()
    private val publisherFolder = UUID.randomUUID()
    private val e1 = SourceItemId(UUID.randomUUID().toString())
    private val e2 = SourceItemId(UUID.randomUUID().toString())
    private val e3 = SourceItemId(UUID.randomUUID().toString())
    private val e4 = SourceItemId(UUID.randomUUID().toString())

    @Test
    fun allKnownEpisodesInTheNarrowedListingKeepTheNarrowing() {
        val known = linkedSetOf(e1, e2, e3, e4)
        val lookedUp = mapOf(e1 to programFolder, e2 to programFolder, e3 to programFolder)

        assertEquals(BookNarrowing.Narrow(programFolder), BookProgramNarrowing.chooseParent(known, lookedUp))
        // 絞り込みの結果に既知でない各回（新しく足された回）があってもよい
        val newEpisode = SourceItemId(UUID.randomUUID().toString())
        assertEquals(BookNarrowing.Narrow(programFolder), BookProgramNarrowing.judge(known, lookedUp, listOf(newEpisode, e1, e2, e3, e4)))
    }

    @Test
    fun onlyTheNewestFewAreLookedUp() {
        assertEquals(listOf(e1, e2, e3), BookProgramNarrowing.sample(linkedSetOf(e1, e2, e3, e4)))
        assertEquals(listOf(e1), BookProgramNarrowing.sample(setOf(e1)))
    }

    @Test
    fun noKnownEpisodesFallsBackToTheFullListing() {
        val expected = BookNarrowing.FullListing(FullListingReason.NO_KNOWN_EPISODES)
        assertEquals(expected, BookProgramNarrowing.chooseParent(emptySet(), emptyMap()))
        assertEquals(expected, BookProgramNarrowing.judge(emptySet(), emptyMap(), listOf(e1)))
    }

    @Test
    fun knownEpisodeMissingOnTheServerFallsBackToTheFullListing() {
        val known = linkedSetOf(e1, e2)
        // e2 は ids= で引いても返らなかった
        val lookedUp = mapOf(e1 to programFolder)
        val expected = BookNarrowing.FullListing(FullListingReason.KNOWN_EPISODE_NOT_ON_SERVER)
        assertEquals(expected, BookProgramNarrowing.chooseParent(known, lookedUp))
        assertEquals(expected, BookProgramNarrowing.judge(known, lookedUp, listOf(e1)))
    }

    @Test
    fun knownEpisodeNotInTheNarrowedListingFallsBackToTheFullListing() {
        val known = linkedSetOf(e1, e2, e3, e4)
        val lookedUp = mapOf(e1 to programFolder, e2 to programFolder, e3 to programFolder)
        // ids= で引かなかった古い e4 が、親の直下に無い
        assertEquals(
            BookNarrowing.FullListing(FullListingReason.KNOWN_EPISODE_NOT_IN_NARROWED),
            BookProgramNarrowing.judge(known, lookedUp, listOf(e1, e2, e3)),
        )
    }

    @Test
    fun knownEpisodesUnderDifferentParentsFallBackToTheFullListing() {
        // 1 本だけの番組に 2 本目を足した途中など、引いた各回の親が分かれている
        val known = linkedSetOf(e1, e2)
        val lookedUp = mapOf(e1 to programFolder, e2 to publisherFolder)
        val expected = BookNarrowing.FullListing(FullListingReason.KNOWN_EPISODE_NOT_IN_NARROWED)
        assertEquals(expected, BookProgramNarrowing.chooseParent(known, lookedUp))
        assertEquals(expected, BookProgramNarrowing.judge(known, lookedUp, listOf(e1, e2)))
        // 親が無い各回も同じ
        assertEquals(expected, BookProgramNarrowing.chooseParent(setOf(e1), mapOf(e1 to null)))
    }

    /** 既知の各回を渡しているのに全件の一覧に番組が無ければ、消失ではなく同期の失敗（何も消さない）。 */
    @Test
    fun programMissingFromTheFullListingWithKnownEpisodesIsAFailure() {
        assertEquals(MissingProgram.FAILED, BookProgramNarrowing.missingFromFullListing(setOf(e1)))
        assertEquals(MissingProgram.FAILED, BookProgramNarrowing.missingFromFullListing(linkedSetOf(e1, e2, e3, e4)))
    }

    /** 既知の各回が無ければ、全件の一覧に番組が無いのは今までどおり消失。 */
    @Test
    fun programMissingFromTheFullListingWithoutKnownEpisodesIsGone() {
        assertEquals(MissingProgram.GONE, BookProgramNarrowing.missingFromFullListing(emptySet()))
    }
}
