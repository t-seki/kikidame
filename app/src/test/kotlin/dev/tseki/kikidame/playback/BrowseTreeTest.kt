package dev.tseki.kikidame.playback
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.Episode
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LocalFile
import dev.tseki.kikidame.domain.PlaybackState
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
/** Android Auto のブラウズツリー（#96）: タブの出し分け、手元にある回だけ、再生済み／途中の印。 */
@RunWith(AndroidJUnit4::class)
class BrowseTreeTest {
    private val now = Instant.parse("2026-09-20T00:00:00Z")
    private val library = InMemoryLibraryRepository()
    private val tree = BrowseTree(library, BrowseTree.Labels(starred = "Starred", programs = "Programs"))
    private val harai = Program(id = ProgramId(1), serverItemId = null, name = "ハライチのターン！", publisherName = "TBSラジオ", starred = true)
    private val ann = Program(id = ProgramId(2), serverItemId = null, name = "ANN", publisherName = "ニッポン放送")
    private val onlyOnServer = Program(id = ProgramId(3), serverItemId = null, name = "手元に無い番組", publisherName = null, starred = true)
    private fun episode(id: Long, programId: ProgramId, published: String, local: Boolean = true, playback: PlaybackState? = null) =
        EpisodeWithState(
            episode = Episode(
                id = EpisodeId(id),
                serverItemId = null,
                programId = programId,
                title = published,
                publishedAt = Instant.parse("${published}T15:00:00Z"),
                addedAt = null,
                runtime = 60.minutes,
                sizeBytes = 1,
                container = "m4a",
            ),
            localFile = if (local) LocalFile(EpisodeId(id), DownloadState.DONE, "/tmp/$id.m4a", pinned = false) else null,
            playback = playback,
        )
    private fun setUp(vararg episodes: EpisodeWithState, programs: List<Program> = listOf(harai, ann, onlyOnServer)) {
        library.programs.value = programs
        library.episodes.value = episodes.toList()
    }
    @Test
    fun rootHasStarredTabOnlyWhenAStarredProgramHasLocalEpisodes() = runTest {
        setUp(episode(10, harai.id, "2026-09-18"), episode(20, ann.id, "2026-09-19"))
        assertEquals(listOf(BrowseTree.STARRED_ID, BrowseTree.PROGRAMS_ID), tree.children(BrowseTree.ROOT_ID)!!.map { it.mediaId })
        // よく聴くの印はあっても手元に回が無い番組しか無ければ、タブごと出さない
        setUp(episode(20, ann.id, "2026-09-19"))
        assertEquals(listOf(BrowseTree.PROGRAMS_ID), tree.children(BrowseTree.ROOT_ID)!!.map { it.mediaId })
    }
    @Test
    fun tabsAreBrowsableFoldersWithLabels() = runTest {
        setUp(episode(10, harai.id, "2026-09-18"))
        val tabs = tree.children(BrowseTree.ROOT_ID)!!
        assertEquals(listOf("Starred", "Programs"), tabs.map { it.mediaMetadata.title })
        assertTrue(tabs.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
    }
    @Test
    fun programsTabListsOnlyProgramsWithLocalEpisodesInRepositoryOrder() = runTest {
        setUp(episode(10, harai.id, "2026-09-18"), episode(20, ann.id, "2026-09-19"), episode(30, onlyOnServer.id, "2026-09-19", local = false))
        val programs = tree.children(BrowseTree.PROGRAMS_ID)!!
        assertEquals(listOf(BrowseTree.programId(harai.id), BrowseTree.programId(ann.id)), programs.map { it.mediaId })
        assertEquals("ハライチのターン！", programs[0].mediaMetadata.title)
        assertEquals("TBSラジオ", programs[0].mediaMetadata.subtitle)
        assertEquals(MediaMetadata.MEDIA_TYPE_PODCAST, programs[0].mediaMetadata.mediaType)
    }
    @Test
    fun starredTabListsOnlyStarredPrograms() = runTest {
        setUp(episode(10, harai.id, "2026-09-18"), episode(20, ann.id, "2026-09-19"))
        assertEquals(listOf(BrowseTree.programId(harai.id)), tree.children(BrowseTree.STARRED_ID)!!.map { it.mediaId })
    }
    @Test
    fun programListsLocalEpisodesNewestFirstWithPublishedDate() = runTest {
        setUp(episode(10, harai.id, "2026-09-11"), episode(11, harai.id, "2026-09-18"), episode(12, harai.id, "2026-09-04", local = false))
        val episodes = tree.children(BrowseTree.programId(harai.id))!!
        assertEquals(listOf("11", "10"), episodes.map { it.mediaId })
        val newest = episodes[0].mediaMetadata
        assertEquals("2026-09-18", newest.title)
        assertEquals("ハライチのターン！", newest.artist)
        assertEquals("TBSラジオ", newest.albumTitle)
        assertEquals("2026-09-18", newest.displayTitle) // 置かないと legacy 変換で subtitle が捨てられる（#113）
        assertEquals("2026-09-19 · ハライチのターン！", newest.subtitle) // JST の公開日 · 番組名
        assertEquals("TBSラジオ", newest.description)
        assertTrue(newest.isPlayable == true && newest.isBrowsable == false)
        assertEquals(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE, newest.mediaType)
    }
    @Test
    fun episodeCarriesCompletionStatus() = runTest {
        setUp(
            episode(10, harai.id, "2026-09-11", playback = PlaybackState(EpisodeId(10), 30.minutes, played = false, updatedAt = now)),
            episode(11, harai.id, "2026-09-18", playback = PlaybackState(EpisodeId(11), 59.minutes, played = true, updatedAt = now)),
            episode(12, harai.id, "2026-09-04"),
        )
        val byId = tree.children(BrowseTree.programId(harai.id))!!.associateBy { it.mediaId }
        val partial = byId.getValue("10").mediaMetadata.extras!!
        assertEquals(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED, partial.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS))
        assertEquals(0.5, partial.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE), 1e-9)
        val played = byId.getValue("11").mediaMetadata.extras!!
        assertEquals(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED, played.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS))
        val fresh = byId.getValue("12").mediaMetadata.extras!!
        assertEquals(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED, fresh.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS))
    }
    /** タイトルが公開日と同じ文字列の回では公開日を省き、2 行目は番組名だけ（アプリの各回一覧と同じ規則。#66）。 */
    @Test
    fun subtitleOmitsPublishedDateWhenTitleEqualsIt() {
        assertEquals("ANN", BrowseTree.episodeSubtitle("2026-09-19", "2026-09-19", "ANN"))
        assertEquals("2026-09-19 · ANN", BrowseTree.episodeSubtitle("第 12 回", "2026-09-19", "ANN"))
        assertEquals("2026-09-19 · ANN", BrowseTree.episodeSubtitle("2026-09-19 (1)", "2026-09-19", "ANN"))
    }
    /** 聴き始めていない（数秒だけ）回は「途中」にしない — アプリの各回一覧と同じ規則。 */
    @Test
    fun barelyStartedEpisodeIsNotPartiallyPlayed() {
        val state = PlaybackState(EpisodeId(1), Duration.parse("3s"), played = false, updatedAt = now)
        val extras = BrowseTree.completionExtras(state, 60.minutes)
        assertEquals(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED, extras.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS))
    }
    @Test
    fun unknownIdsReturnNull() = runTest {
        setUp(episode(10, harai.id, "2026-09-11"))
        assertNull(tree.children("nope"))
        assertNull(tree.children(BrowseTree.programId(ProgramId(99))))
        assertNull(tree.item("program:x"))
        assertNull(tree.item("99")) // 無い各回
        assertEquals(emptyList(), tree.children("10")) // 各回は葉
    }
    @Test
    fun parseRoundTrips() {
        assertEquals(BrowseTree.Node.Root, BrowseTree.parse(BrowseTree.ROOT_ID))
        assertEquals(BrowseTree.Node.Program(ProgramId(7)), BrowseTree.parse(BrowseTree.programId(ProgramId(7))))
        assertEquals(BrowseTree.Node.Episode(EpisodeId(42)), BrowseTree.parse(EpisodeMediaItems.mediaId(EpisodeId(42))))
        assertNull(BrowseTree.parse("program:"))
    }
    @Test
    fun completionPercentageIsClampedAndSafeForUnknownRuntime() {
        assertEquals(0.0, BrowseTree.completionPercentage(10.minutes, Duration.ZERO), 0.0)
        assertEquals(1.0, BrowseTree.completionPercentage(90.minutes, 60.minutes), 0.0)
        assertEquals(0.25, BrowseTree.completionPercentage(15.minutes, 60.minutes), 1e-9)
    }
}
