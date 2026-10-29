package dev.tseki.kikidame.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.RetentionRule
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** 再取り込み（取得元 ID の変更）後の結び直し（ADR 0001）と、タイトルが違う未結合の各回の第二段突合（公開日 + 尺）。 */
@RunWith(AndroidJUnit4::class)
class RoomRematchTest : JellyfinRefreshTestBase() {
    private val publisher = "TBSラジオ"
    private val programName = "パンサー向井のふらっと"

    private fun snapshot(program: String, vararg episodes: SourceEpisode) =
        SourceSnapshot(listOf(SourceProgram(SourceItemId(program), programName, publisher)), episodes.toList())

    private val original = snapshot("P1", sourceEpisode("E1", "2026-09-01", "2026-09-01", program = "P1"), sourceEpisode("E2", "2026-09-02", "2026-09-02", program = "P1"), sourceEpisode("E3", "2026-09-03", "2026-09-03", program = "P1"))

    private suspend fun programs() = library.observePrograms().first()

    private suspend fun episodes(programId: ProgramId): List<EpisodeWithState> = library.observeEpisodes(programId).first()

    private suspend fun episode(title: String): EpisodeWithState =
        programs().flatMap { episodes(it.program.id) }.first { it.episode.title == title }

    private suspend fun markDone(title: String, pinned: Boolean): File {
        val e = episode(title)
        return markDoneAt(e.episode.id.value, File(directory.root, "${e.episode.id.value}.m4a"), pinned)
    }

    @Test
    fun reimportWithNewProgramAndEpisodeIdsKeepsRowsFilesAndPlayback() = runTest {
        signInAndSelect()
        gateway.snapshot = original
        repo.refresh()
        val programId = programs().single().program.id
        library.updateSync(programId, true, RetentionRule(keepLatest = 3))
        val file = markDone("2026-09-02", pinned = false)
        val e2 = episode("2026-09-02").episode.id
        playback.update(e2) { PlaybackRules.advance(it, 12.minutes, 90.minutes, now) }

        // ライブラリを作り直した: 番組も各回も ID が全部変わり、各回が 1 本増えた
        gateway.snapshot = snapshot(
            "P9",
            sourceEpisode("N1", "2026-09-01", "2026-09-01", program = "P9"), sourceEpisode("N2", "2026-09-02", "2026-09-02", program = "P9"),
            sourceEpisode("N3", "2026-09-03", "2026-09-03", program = "P9"), sourceEpisode("N4", "2026-09-04", "2026-09-04", program = "P9"),
        )
        val result = repo.refresh()

        assertEquals(1, programs().size, "no duplicate program row")
        assertEquals("P9", programs().single().program.sourceItemId?.value)
        assertEquals(0, result.removed)
        assertEquals(0, result.onHold)
        val relinked = episode("2026-09-02")
        assertEquals(e2, relinked.episode.id, "same local row")
        assertEquals("N2", relinked.episode.sourceItemId?.value)
        assertEquals(12.minutes, relinked.playback?.position)
        assertEquals(DownloadState.DONE, relinked.localFile?.state)
        assertTrue(file.exists())
        assertEquals(4, episodes(programId).size)
        assertEquals(2, result.enqueued, "keepLatest = 3 → 09-04 and 09-03 (09-02 is already here)")
    }

    @Test
    fun episodeIdChurnInsideAKnownProgramDoesNotRemoveAnything() = runTest {
        signInAndSelect()
        gateway.snapshot = original
        repo.refresh()
        val programId = programs().single().program.id
        val pinned = markDone("2026-09-01", pinned = true)
        val e1 = episode("2026-09-01").episode.id

        gateway.snapshot = snapshot("P1", sourceEpisode("N1", "2026-09-01", "2026-09-01", program = "P1"), sourceEpisode("N2", "2026-09-02", "2026-09-02", program = "P1"), sourceEpisode("N3", "2026-09-03", "2026-09-03", program = "P1"))
        val result = repo.refresh()

        assertEquals(0, result.removed)
        assertTrue(pinned.exists())
        assertEquals(e1, episode("2026-09-01").episode.id)
        assertEquals("N1", episode("2026-09-01").episode.sourceItemId?.value)
        assertEquals(3, episodes(programId).size)
    }

    @Test
    fun anEpisodeThatReallyVanishedIsStillRemovedAfterRelinking() = runTest {
        signInAndSelect()
        gateway.snapshot = original
        repo.refresh()
        val programId = programs().single().program.id
        val goneFile = markDone("2026-09-03", pinned = true)

        gateway.snapshot = snapshot("P1", sourceEpisode("N1", "2026-09-01", "2026-09-01", program = "P1"), sourceEpisode("N2", "2026-09-02", "2026-09-02", program = "P1"))
        val result = repo.refresh()

        assertEquals(1, result.removed)
        assertTrue(!goneFile.exists())
        assertEquals(listOf("2026-09-02", "2026-09-01"), episodes(programId).map { it.episode.title })
    }

    @Test
    fun programSyncRelinksEpisodesOfThatProgram() = runTest {
        signInAndSelect()
        gateway.snapshot = original
        repo.refresh()
        val programId = programs().single().program.id
        library.updateSync(programId, true, RetentionRule(keepLatest = 3))
        val file = markDone("2026-09-01", pinned = false)

        gateway.snapshot = snapshot("P1", sourceEpisode("N1", "2026-09-01", "2026-09-01", program = "P1"), sourceEpisode("N2", "2026-09-02", "2026-09-02", program = "P1"), sourceEpisode("N3", "2026-09-03", "2026-09-03", program = "P1"))
        val result = assertNotNull(repo.syncProgram(programId))

        assertEquals(0, result.removed)
        assertTrue(file.exists())
        assertEquals("N1", episode("2026-09-01").episode.sourceItemId?.value)
    }

    @Test
    fun unlinkedEpisodesLinkByPublishedDayAndRuntimeWhenTitlesDiffer() = runTest {
        seed(
            listOf(
                scanned(publisher = publisher, program = programName, title = "$programName 2026-09-16-1", publishedAt = "2026-09-15T15:00:00Z", runtime = 89.minutes + 59.seconds),
                scanned(publisher = publisher, program = programName, title = "$programName 2026-09-16-2", publishedAt = "2026-09-15T15:00:00Z", runtime = 60.minutes + 5.seconds),
            ),
        )
        val seeded = programs().single()
        val listened = episodes(seeded.program.id).first { it.episode.title.endsWith("-2") }
        playback.update(listened.episode.id) { PlaybackRules.advance(it, 20.minutes, 60.minutes, now) }
        signInAndSelect()
        gateway.snapshot = snapshot(
            "P1",
            // 手元の行の公開日（JST 0 時 = 前日 15:00Z）と同じ日
            sourceEpisode("E1", "2026-09-16 (1)", "2026-09-15T15:00:00Z", program = "P1", runtime = 90.minutes),
            sourceEpisode("E2", "2026-09-16 (2)", "2026-09-15T15:00:00Z", program = "P1", runtime = 60.minutes + 4.seconds),
        )

        val result = repo.refresh()

        assertEquals(2, result.linkedEpisodes)
        val rows = episodes(seeded.program.id)
        assertEquals(2, rows.size, "seed rows and server rows are the same rows now")
        assertEquals(setOf("2026-09-16 (1)", "2026-09-16 (2)"), rows.map { it.episode.title }.toSet(), "server titles overwrite the file names")
        val part2 = rows.first { it.episode.title == "2026-09-16 (2)" }
        assertEquals(listened.episode.id, part2.episode.id)
        assertEquals(20.minutes, part2.playback?.position)
        assertTrue(part2.localFile?.pinned == true, "the seeded file stays and stays pinned")
    }
}
