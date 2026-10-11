package dev.tseki.kikidame.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.LocalDeletionScope
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class RoomDownloadRepositoryTest : JellyfinRefreshTestBase() {
    private val snapshot = SourceSnapshot(
        programs = listOf(SourceProgram(SourceItemId("album-1"), "パンサー向井のふらっと", "TBSラジオ")),
        episodes = listOf(
            SourceEpisode(SourceItemId("a1"), SourceItemId("album-1"), "2026-09-16 (1)", Instant.parse("2026-09-15T15:00:00Z"), null, 90.minutes, null, "m4a"),
            SourceEpisode(SourceItemId("a2"), SourceItemId("album-1"), "2026-09-15 (1)", Instant.parse("2026-09-14T15:00:00Z"), null, 90.minutes, null, "m4a"),
        ),
    )

    @Before
    fun seedServerRows() = runTest {
        repo.apply(snapshot)
        directory.root!!.deleteRecursively()
    }

    private suspend fun episodeId(title: String): EpisodeId {
        val program = library.observePrograms().first().single().program.id
        return library.observeEpisodes(program).first().first { it.episode.title == title }.episode.id
    }

    @Test
    fun enqueueCreatesPendingPinnedRowWithTargetPath() = runTest {
        val id = episodeId("2026-09-16 (1)")
        downloads.enqueue(id)
        val row = db.localFileDao().findByEpisode(id.value)!!
        assertEquals(DownloadState.PENDING, row.state)
        assertTrue(row.pinned)
        assertTrue(row.path!!.endsWith("/episodes/TBSラジオ/パンサー向井のふらっと/2026-09-16 (1).m4a"), row.path)
        downloads.enqueue(id)
        assertEquals(DownloadState.PENDING, db.localFileDao().findByEpisode(id.value)!!.state, "idempotent")
    }

    @Test
    fun enqueueAvoidsExistingFileNames() = runTest {
        val id = episodeId("2026-09-16 (1)")
        val taken = directory.resolve("TBSラジオ/パンサー向井のふらっと/2026-09-16 (1).m4a")
        taken.parentFile!!.mkdirs()
        taken.writeText("seeded")
        downloads.enqueue(id)
        assertTrue(db.localFileDao().findByEpisode(id.value)!!.path!!.endsWith("2026-09-16 (1) (2).m4a"))
    }

    @Test
    fun queueOrderIsFifoAndDoneUpdatesSize() = runTest {
        val newer = episodeId("2026-09-16 (1)")
        val older = episodeId("2026-09-15 (1)")
        downloads.enqueue(older)
        now += 1.minutes
        downloads.enqueue(newer)
        assertEquals(older, downloads.nextPending()!!.episode.id, "tapped first, downloaded first")

        downloads.markRunning(older)
        assertTrue(downloads.isStillWanted(older))
        assertEquals(newer, downloads.nextPending()!!.episode.id)
        val target = db.localFileDao().findByEpisode(newer.value)!!.path!!
        downloads.markDone(newer, target, 12345)
        val done = library.getEpisode(newer)!!
        assertEquals(DownloadState.DONE, done.localFile!!.state)
        assertEquals(now, done.localFile!!.downloadedAt)
        assertEquals(12345, done.episode.sizeBytes)
        assertFalse(downloads.isStillWanted(newer))
    }

    @Test
    fun retryGoesToTheBackOfTheQueue() = runTest {
        val first = episodeId("2026-09-16 (1)")
        val second = episodeId("2026-09-15 (1)")
        downloads.enqueue(first)
        now += 1.minutes
        downloads.enqueue(second)
        downloads.markRunning(first)
        downloads.markFailed(first)
        now += 1.minutes
        downloads.retry(first)
        assertEquals(second, downloads.nextPending()!!.episode.id)
    }

    @Test
    fun failedRowsAreRequeuedUntilTheAttemptLimit() = runTest {
        val id = episodeId("2026-09-16 (1)")
        downloads.enqueue(id)
        repeat(3) {
            downloads.markRunning(id)
            downloads.markFailed(id)
            downloads.requeueFailed(maxAttempts = 3)
        }
        assertEquals(DownloadState.FAILED, db.localFileDao().findByEpisode(id.value)!!.state)
        assertEquals(3, db.localFileDao().findByEpisode(id.value)!!.attemptCount)
        downloads.retry(id)
        assertEquals(DownloadState.PENDING, db.localFileDao().findByEpisode(id.value)!!.state, "manual retry ignores the limit")
    }

    @Test
    fun cancelRemovesRowAndPartFile() = runTest {
        val id = episodeId("2026-09-16 (1)")
        downloads.enqueue(id)
        val path = db.localFileDao().findByEpisode(id.value)!!.path!!
        File("$path.part").apply { parentFile!!.mkdirs(); writeText("half") }
        downloads.cancel(id)
        assertNull(db.localFileDao().findByEpisode(id.value))
        assertFalse(File("$path.part").exists())
        assertFalse(downloads.isStillWanted(id))
    }

    @Test
    fun deleteSourceEpisodeKeepsRowAndPlaybackState() = runTest {
        val id = episodeId("2026-09-16 (1)")
        downloads.enqueue(id)
        val path = db.localFileDao().findByEpisode(id.value)!!.path!!
        File(path).apply { parentFile!!.mkdirs(); writeText("audio") }
        downloads.markDone(id, path, 5)
        playback.update(id) { PlaybackRules.advance(it, 10.minutes, 90.minutes, now) }

        assertEquals(LocalDeletionScope.FILE_ONLY, downloads.deleteLocal(id))

        assertFalse(File(path).exists())
        val after = library.getEpisode(id)!!
        assertNull(after.localFile)
        assertEquals(10.minutes, after.playback?.position)
    }

    @Test
    fun deleteSeededEpisodeRemovesEpisodeAndEmptyLocalProgram() = runTest {
        seed(listOf(scanned(publisher = "J-WAVE", program = "Solo", title = "Solo 2026-06-12", publishedAt = "2026-06-11T15:00:00Z")))
        val solo = library.observePrograms().first().first { it.program.name == "Solo" }
        val ep = library.observeEpisodes(solo.program.id).first().single()
        playback.update(ep.episode.id) { PlaybackRules.setPlayed(it, true, now) }

        assertEquals(LocalDeletionScope.EPISODE, downloads.deleteLocal(ep.episode.id))

        assertNull(library.getEpisode(ep.episode.id))
        assertNull(db.playbackStateDao().findByEpisode(ep.episode.id.value))
        assertTrue(library.observePrograms().first().none { it.program.name == "Solo" }, "empty seeded program is removed")
        assertNotNull(library.observePrograms().first().firstOrNull { it.program.sourceItemId != null }, "server program untouched")
    }

    @Test
    fun reconcileDropsDoneRowsWhoseFileIsGone() = runTest {
        val id = episodeId("2026-09-16 (1)")
        downloads.enqueue(id)
        val path = db.localFileDao().findByEpisode(id.value)!!.path!!
        downloads.markDone(id, path, 5) // ファイルは作らない = 消えた状態
        val present = episodeId("2026-09-15 (1)")
        downloads.enqueue(present)
        val presentPath = db.localFileDao().findByEpisode(present.value)!!.path!!
        File(presentPath).apply { parentFile!!.mkdirs(); writeText("ok") }
        downloads.markDone(present, presentPath, 2)

        assertEquals(1, downloads.reconcileMissingFiles())
        assertNull(library.getEpisode(id)!!.localFile)
        assertTrue(library.getEpisode(present)!!.isPlayable)

        assertTrue(downloads.ensureFilePresent(present))
        File(presentPath).delete()
        assertFalse(downloads.ensureFilePresent(present))
        assertNull(library.getEpisode(present)!!.localFile)
    }
}
