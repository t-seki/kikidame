package dev.tseki.kikidame.playback
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.Episode
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LocalFile
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.PlaybackState
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
/** 番組のキュー（#231）: 手元にある回を古い順に積み、対象の回から、保存位置の規則（[PlaybackRules.resumePosition]）で始める。 */
@RunWith(AndroidJUnit4::class)
class ProgramQueueTest {
    private val now = Instant.parse("2026-09-20T00:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now
    }
    private val library = InMemoryLibraryRepository()
    private val playbackStates = InMemoryPlaybackStateRepository(clock)
    private val queue = ProgramQueue(library, playbackStates)
    private val harai = Program(id = ProgramId(1), sourceItemId = null, name = "ハライチのターン！", publisherName = "TBSラジオ")
    private val ann = Program(id = ProgramId(2), sourceItemId = null, name = "ANN", publisherName = "ニッポン放送")
    private fun episode(id: Long, programId: ProgramId, published: String, local: Boolean = true) =
        EpisodeWithState(
            episode = Episode(
                id = EpisodeId(id),
                sourceItemId = null,
                programId = programId,
                title = published,
                publishedAt = Instant.parse("${published}T15:00:00Z"),
                addedAt = null,
                runtime = 60.minutes,
                sizeBytes = 1,
                container = "m4a",
            ),
            localFile = if (local) LocalFile(EpisodeId(id), DownloadState.DONE, "/tmp/$id.m4a", pinned = false) else null,
            playback = null,
        )
    private fun setUp(vararg episodes: EpisodeWithState) {
        library.programs.value = listOf(harai, ann)
        library.episodes.value = episodes.toList()
    }
    private fun saved(id: Long, position: Duration) {
        playbackStates.states.value += EpisodeId(id) to PlaybackState(EpisodeId(id), position, played = false, updatedAt = now)
    }
    /** 同じ番組の手元にある回だけが古い順に並び、各回の MediaItem は番組の名前を持つ。 */
    @Test
    fun queuesLocalEpisodesOfTheProgramOldestFirst() = runTest {
        val target = episode(11, harai.id, "2026-09-11")
        setUp(
            episode(18, harai.id, "2026-09-18"),
            target,
            episode(4, harai.id, "2026-09-04"),
            episode(25, harai.id, "2026-09-25", local = false),
            episode(20, ann.id, "2026-09-19"),
        )
        val start = queue.startingAt(target)
        assertEquals(listOf("4", "11", "18"), start.items.map { it.mediaId })
        assertEquals(listOf("ハライチのターン！"), start.items.map { it.mediaMetadata.artist }.distinct())
        assertEquals("2026-09-11", start.items[1].mediaMetadata.title)
    }
    /** 対象の回の位置から始める。 */
    @Test
    fun startsAtTheTargetEpisode() = runTest {
        val target = episode(18, harai.id, "2026-09-18")
        setUp(episode(4, harai.id, "2026-09-04"), episode(11, harai.id, "2026-09-11"), target)
        assertEquals(2, queue.startingAt(target).index)
        assertEquals(0, queue.startingAt(episode(4, harai.id, "2026-09-04")).index)
    }
    /** 保存位置が無ければ頭から、あればその位置から。 */
    @Test
    fun startsFromTheSavedPositionOrTheBeginning() = runTest {
        val target = episode(11, harai.id, "2026-09-11")
        setUp(episode(4, harai.id, "2026-09-04"), target)
        assertEquals(0L, queue.startingAt(target).startMs)
        saved(11, 20.minutes)
        assertEquals(20.minutes.inWholeMilliseconds, queue.startingAt(target).startMs)
        // ほかの回の保存位置は見ない
        saved(4, 30.minutes)
        assertEquals(20.minutes.inWholeMilliseconds, queue.startingAt(target).startMs)
    }
    /** 聴き終えに近い位置（残りが閾値以下）で止めた回は頭から（[PlaybackRules.resumePosition]）。 */
    @Test
    fun startsFromTheBeginningWhenSavedNearTheEnd() = runTest {
        val target = episode(11, harai.id, "2026-09-11")
        setUp(target)
        saved(11, 60.minutes - PlaybackRules.PLAYED_THRESHOLD)
        assertEquals(0L, queue.startingAt(target).startMs)
        saved(11, 60.minutes - PlaybackRules.PLAYED_THRESHOLD - 1.seconds)
        assertEquals((60.minutes - PlaybackRules.PLAYED_THRESHOLD - 1.seconds).inWholeMilliseconds, queue.startingAt(target).startMs)
    }
}
