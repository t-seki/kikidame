package dev.tseki.kikidame.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.data.files.EpisodesDirectory
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.LocalDeletionScope
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.ServerEpisode
import dev.tseki.kikidame.domain.ServerItemId
import dev.tseki.kikidame.domain.ServerProgram
import dev.tseki.kikidame.domain.ServerSnapshot
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
class RoomDownloadRepositoryTest : RoomTestBase() {
    private val directory = EpisodesDirectory(ApplicationProvider.getApplicationContext())
    private val repo by lazy { RoomDownloadRepository(db, directory, clock) }
    private val library by lazy { RoomLibraryRepository(db.programDao(), db.episodeDao(), db.localFileDao()) }
    private val refresh by lazy { RoomLibraryRefreshRepository(db, testSessionStore(tmpDir(), kotlinx.coroutines.GlobalScope), FakeJellyfinGateway(), repo, clock) }
    private val playback by lazy { RoomPlaybackStateRepository(db, clock) }

    private fun tmpDir(): File = File(directory.root, "tmp").apply { mkdirs() }

    private val snapshot = ServerSnapshot(
        programs = listOf(ServerProgram(ServerItemId("album-1"), "パンサー向井のふらっと", "TBSラジオ")),
        episodes = listOf(
            ServerEpisode(ServerItemId("a1"), ServerItemId("album-1"), "2026-09-16 (1)", Instant.parse("2026-09-15T15:00:00Z"), null, 90.minutes, null, "m4a"),
            ServerEpisode(ServerItemId("a2"), ServerItemId("album-1"), "2026-09-15 (1)", Instant.parse("2026-09-14T15:00:00Z"), null, 90.minutes, null, "m4a"),
        ),
    )

    @Before
    fun seedServerRows() = runTest {
        refresh.apply(snapshot)
        directory.root!!.deleteRecursively()
    }

    private suspend fun episodeId(title: String): EpisodeId {
        val program = library.observePrograms().first().single().program.id
        return library.observeEpisodes(program).first().first { it.episode.title == title }.episode.id
    }

    @Test
    fun enqueueCreatesPendingPinnedRowWithTargetPath() = runTest {
        val id = episodeId("2026-09-16 (1)")
        repo.enqueue(id)
        val row = db.localFileDao().findByEpisode(id.value)!!
        assertEquals(DownloadState.PENDING, row.state)
        assertTrue(row.pinned)
        assertTrue(row.path!!.endsWith("/episodes/TBSラジオ/パンサー向井のふらっと/2026-09-16 (1).m4a"), row.path)
        repo.enqueue(id)
        assertEquals(DownloadState.PENDING, db.localFileDao().findByEpisode(id.value)!!.state, "idempotent")
    }

    @Test
    fun enqueueAvoidsExistingFileNames() = runTest {
        val id = episodeId("2026-09-16 (1)")
        val taken = directory.resolve("TBSラジオ/パンサー向井のふらっと/2026-09-16 (1).m4a")
        taken.parentFile!!.mkdirs()
        taken.writeText("seeded")
        repo.enqueue(id)
        assertTrue(db.localFileDao().findByEpisode(id.value)!!.path!!.endsWith("2026-09-16 (1) (2).m4a"))
    }

    @Test
    fun queueOrderIsFifoAndDoneUpdatesSize() = runTest {
        val newer = episodeId("2026-09-16 (1)")
        val older = episodeId("2026-09-15 (1)")
        repo.enqueue(older)
        now += 1.minutes
        repo.enqueue(newer)
        assertEquals(older, repo.nextPending()!!.episode.id, "tapped first, downloaded first")

        repo.markRunning(older)
        assertTrue(repo.isStillWanted(older))
        assertEquals(newer, repo.nextPending()!!.episode.id)
        val target = db.localFileDao().findByEpisode(newer.value)!!.path!!
        repo.markDone(newer, target, 12345)
        val done = library.getEpisode(newer)!!
        assertEquals(DownloadState.DONE, done.localFile!!.state)
        assertEquals(now, done.localFile!!.downloadedAt)
        assertEquals(12345, done.episode.sizeBytes)
        assertFalse(repo.isStillWanted(newer))
    }

    @Test
    fun retryGoesToTheBackOfTheQueue() = runTest {
        val first = episodeId("2026-09-16 (1)")
        val second = episodeId("2026-09-15 (1)")
        repo.enqueue(first)
        now += 1.minutes
        repo.enqueue(second)
        repo.markRunning(first)
        repo.markFailed(first)
        now += 1.minutes
        repo.retry(first)
        assertEquals(second, repo.nextPending()!!.episode.id)
    }

    @Test
    fun failedRowsAreRequeuedUntilTheAttemptLimit() = runTest {
        val id = episodeId("2026-09-16 (1)")
        repo.enqueue(id)
        repeat(3) {
            repo.markRunning(id)
            repo.markFailed(id)
            repo.requeueFailed(maxAttempts = 3)
        }
        assertEquals(DownloadState.FAILED, db.localFileDao().findByEpisode(id.value)!!.state)
        assertEquals(3, db.localFileDao().findByEpisode(id.value)!!.attemptCount)
        repo.retry(id)
        assertEquals(DownloadState.PENDING, db.localFileDao().findByEpisode(id.value)!!.state, "manual retry ignores the limit")
    }

    @Test
    fun cancelRemovesRowAndPartFile() = runTest {
        val id = episodeId("2026-09-16 (1)")
        repo.enqueue(id)
        val path = db.localFileDao().findByEpisode(id.value)!!.path!!
        File("$path.part").apply { parentFile!!.mkdirs(); writeText("half") }
        repo.cancel(id)
        assertNull(db.localFileDao().findByEpisode(id.value))
        assertFalse(File("$path.part").exists())
        assertFalse(repo.isStillWanted(id))
    }

    @Test
    fun deleteServerEpisodeKeepsRowAndPlaybackState() = runTest {
        val id = episodeId("2026-09-16 (1)")
        repo.enqueue(id)
        val path = db.localFileDao().findByEpisode(id.value)!!.path!!
        File(path).apply { parentFile!!.mkdirs(); writeText("audio") }
        repo.markDone(id, path, 5)
        playback.update(id) { PlaybackRules.advance(it, 10.minutes, 90.minutes, now) }

        assertEquals(LocalDeletionScope.FILE_ONLY, repo.deleteLocal(id))

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

        assertEquals(LocalDeletionScope.EPISODE, repo.deleteLocal(ep.episode.id))

        assertNull(library.getEpisode(ep.episode.id))
        assertNull(db.playbackStateDao().findByEpisode(ep.episode.id.value))
        assertTrue(library.observePrograms().first().none { it.program.name == "Solo" }, "empty seeded program is removed")
        assertNotNull(library.observePrograms().first().firstOrNull { it.program.serverItemId != null }, "server program untouched")
    }

    @Test
    fun reconcileDropsDoneRowsWhoseFileIsGone() = runTest {
        val id = episodeId("2026-09-16 (1)")
        repo.enqueue(id)
        val path = db.localFileDao().findByEpisode(id.value)!!.path!!
        repo.markDone(id, path, 5) // ファイルは作らない = 消えた状態
        val present = episodeId("2026-09-15 (1)")
        repo.enqueue(present)
        val presentPath = db.localFileDao().findByEpisode(present.value)!!.path!!
        File(presentPath).apply { parentFile!!.mkdirs(); writeText("ok") }
        repo.markDone(present, presentPath, 2)

        assertEquals(1, repo.reconcileMissingFiles())
        assertNull(library.getEpisode(id)!!.localFile)
        assertTrue(library.getEpisode(present)!!.isPlayable)

        assertTrue(repo.ensureFilePresent(present))
        File(presentPath).delete()
        assertFalse(repo.ensureFilePresent(present))
        assertNull(library.getEpisode(present)!!.localFile)
    }
}
