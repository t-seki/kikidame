package dev.tseki.kikidame.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.data.db.EpisodeEntity
import dev.tseki.kikidame.data.db.KikidameDatabase
import dev.tseki.kikidame.data.db.LocalFileEntity
import dev.tseki.kikidame.data.db.ProgramEntity
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

    // --- 別の経路と重なったとき。行を書く文の直前に、別の経路の書き込みを差し込む ---

    @Test
    fun markRunningDoesNotBringBackACancelledRow() = runTest {
        val after = race(row(DownloadState.PENDING), "DELETE FROM local_files") { markRunning(it) }
        assertNull(after, "cancelled while the worker was starting")
    }

    @Test
    fun markRunningKeepsAPinSetByEnqueue() = runTest {
        val after = race(row(DownloadState.PENDING, pinned = false), "UPDATE local_files SET pinned = 1") { markRunning(it) }
        assertEquals(DownloadState.RUNNING, after!!.state)
        assertTrue(after.pinned, "the manual download that upgraded the sync reservation is kept")
    }

    @Test
    fun markFailedDoesNotBringBackADeletedRow() = runTest {
        val after = race(row(DownloadState.RUNNING), "DELETE FROM local_files") { markFailed(it) }
        assertNull(after)
    }

    @Test
    fun resetToPendingDoesNotBringBackACancelledRow() = runTest {
        val after = race(row(DownloadState.RUNNING), "DELETE FROM local_files") { resetToPending(it) }
        assertNull(after)
    }

    @Test
    fun retryDoesNotPutARunningRowBackToPending() = runTest {
        val after = race(row(DownloadState.FAILED), "UPDATE local_files SET state = 'RUNNING'") { retry(it) }
        assertEquals(DownloadState.RUNNING, after!!.state, "the worker already picked it up")
    }

    @Test
    fun pinPinsAFinishedFileWithoutTouchingTheFile() = runTest {
        val id = episodeId("2026-09-16 (1)")
        val file = directory.resolve("a1.m4a").apply { parentFile!!.mkdirs(); writeText("audio") }
        db.localFileDao().upsert(LocalFileEntity(episodeId = id.value, state = DownloadState.DONE, path = file.path, pinned = false, enqueuedAt = now, downloadedAt = now))
        downloads.pin(id)
        val row = db.localFileDao().findByEpisode(id.value)!!
        assertTrue(row.pinned)
        assertEquals(DownloadState.DONE, row.state)
        assertEquals(file.path, row.path)
        assertEquals("audio", file.readText())
    }

    @Test
    fun pinLeavesAnUnfinishedRowAndAMissingRowAlone() = runTest {
        val pending = episodeId("2026-09-16 (1)")
        db.localFileDao().upsert(LocalFileEntity(episodeId = pending.value, state = DownloadState.PENDING, path = null, pinned = false, enqueuedAt = now))
        downloads.pin(pending)
        assertFalse(db.localFileDao().findByEpisode(pending.value)!!.pinned)
        val none = episodeId("2026-09-15 (1)")
        downloads.pin(none)
        assertNull(db.localFileDao().findByEpisode(none.value))
    }

    @Test
    fun unpinDoesNotUndoAFinishedDownload() = runTest {
        val after = race(row(DownloadState.RUNNING), "UPDATE local_files SET state = 'DONE', downloadedAt = 1") { unpin(it) }
        assertEquals(DownloadState.DONE, after!!.state)
        assertFalse(after.pinned)
    }

    @Test
    fun cancelKeepsADownloadThatFinishedMeanwhile() = runTest {
        val after = race(row(DownloadState.RUNNING), "UPDATE local_files SET state = 'DONE', downloadedAt = 1") { cancel(it) }
        assertEquals(DownloadState.DONE, after?.state, "the finished file keeps its row")
    }

    private fun row(state: DownloadState, pinned: Boolean = true) =
        LocalFileEntity(episodeId = 0, state = state, path = "${directory.root}/race.m4a", pinned = pinned, enqueuedAt = now)

    /**
     * 別の DB に 1 回分の行（[initial]）を置き、[op] が `local_files` を書く文（INSERT / UPDATE / DELETE）を流す直前に、
     * 別の経路の書き込み [competing]（`WHERE episodeId = ?` は付け足す）を 1 回だけ流す。終わった後の行を返す。
     * 読んでから書く間に別の経路が書いた、という重なりを決まった順で起こすためのもの。
     */
    private suspend fun race(
        initial: LocalFileEntity,
        competing: String,
        op: suspend RoomDownloadRepository.(EpisodeId) -> Unit,
    ): LocalFileEntity? {
        var pending: (() -> Unit)? = null
        lateinit var raceDb: KikidameDatabase
        raceDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KikidameDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback({ sql, _ ->
                val write = sql.trimStart().uppercase()
                val touchesRow = write.startsWith("INSERT") || write.startsWith("UPDATE") || write.startsWith("DELETE")
                if (touchesRow && "LOCAL_FILES" in write) pending?.also { pending = null }?.invoke()
            }, Runnable::run)
            .build()
        try {
            val programId = raceDb.programDao().insert(ProgramEntity(sourceItemId = "p", name = "P", publisherName = null))
            val episodeId = raceDb.episodeDao().insert(
                EpisodeEntity(
                    sourceItemId = "e",
                    programId = programId,
                    title = "E",
                    publishedAt = now,
                    addedAt = now,
                    runtimeTicks = 0,
                    sizeBytes = 0,
                    container = "m4a",
                ),
            )
            raceDb.localFileDao().upsert(initial.copy(episodeId = episodeId))
            pending = { raceDb.openHelper.writableDatabase.execSQL("$competing WHERE episodeId = $episodeId") }
            RoomDownloadRepository(raceDb, directory, clock).op(EpisodeId(episodeId))
            assertNull(pending, "the competing write ran")
            return raceDb.localFileDao().findByEpisode(episodeId)
        } finally {
            raceDb.close()
        }
    }
}
