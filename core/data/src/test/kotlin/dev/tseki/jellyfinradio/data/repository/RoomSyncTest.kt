package dev.tseki.jellyfinradio.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.jellyfinradio.data.db.LocalFileEntity
import dev.tseki.jellyfinradio.data.files.EpisodesDirectory
import dev.tseki.jellyfinradio.domain.DownloadState
import dev.tseki.jellyfinradio.domain.EpisodeWithState
import dev.tseki.jellyfinradio.domain.LibraryView
import dev.tseki.jellyfinradio.domain.PlaybackRules
import dev.tseki.jellyfinradio.domain.ProgramId
import dev.tseki.jellyfinradio.domain.RetentionRule
import dev.tseki.jellyfinradio.domain.ServerEpisode
import dev.tseki.jellyfinradio.domain.ServerItemId
import dev.tseki.jellyfinradio.domain.ServerProgram
import dev.tseki.jellyfinradio.domain.ServerSnapshot
import dev.tseki.jellyfinradio.domain.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 全走査 = 同期（ADR 0004）の適用と、番組単位の更新（#12）が削除しないこと。 */
@RunWith(AndroidJUnit4::class)
class RoomSyncTest : RoomTestBase() {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gateway = FakeJellyfinGateway()
    private val store by lazy { testSessionStore(tmp.root, scope) }
    private val session by lazy { DataStoreSessionRepository(store, gateway) }
    private val library by lazy { RoomLibraryRepository(db.programDao(), db.episodeDao(), db.localFileDao()) }
    private val playback by lazy { RoomPlaybackStateRepository(db, clock) }
    private val directory by lazy { EpisodesDirectory(ApplicationProvider.getApplicationContext()) }
    private val downloads by lazy { RoomDownloadRepository(db, directory, clock) }
    private val repo by lazy { RoomLibraryRefreshRepository(db, store, gateway, downloads, clock) }

    private fun se(id: String, aired: String, program: String = "album-1") = ServerEpisode(
        serverId = ServerItemId(id),
        programServerId = ServerItemId(program),
        title = aired,
        airedAt = Instant.parse("${aired}T00:00:00Z"),
        addedAt = Instant.parse("2026-09-16T02:00:00Z"),
        runtime = 60.minutes,
        sizeBytes = null,
        container = "m4a",
    )

    private val albumA = ServerProgram(ServerItemId("album-1"), "番組 A", "TBSラジオ")
    private val albumB = ServerProgram(ServerItemId("album-2"), "番組 B", "TBSラジオ")

    private val threeEpisodes = ServerSnapshot(
        programs = listOf(albumA),
        episodes = listOf(se("a1", "2026-09-01"), se("a2", "2026-09-02"), se("a3", "2026-09-03")),
    )

    private suspend fun signInAndSelect() {
        session.signIn("jellyfin.lab.example", "alice", "secret")
        session.selectLibrary(LibraryView(ServerItemId("lib-1"), "Radio", "music", isMusic = true))
    }

    private suspend fun programId(): ProgramId = library.observePrograms().first().single().program.id

    private suspend fun episodes(): List<EpisodeWithState> = library.observeEpisodes(programId()).first()

    private suspend fun episode(serverId: String): EpisodeWithState =
        library.observePrograms().first().flatMap { library.observeEpisodes(it.program.id).first() }
            .first { it.episode.serverItemId?.value == serverId }

    /** ファイルを作って DONE にする（ダウンロード完了の代わり）。 */
    private suspend fun markDone(serverId: String, pinned: Boolean): File {
        val e = episode(serverId)
        val file = File(directory.root, "${e.episode.title}.m4a").apply { parentFile?.mkdirs(); writeBytes(ByteArray(8)) }
        db.localFileDao().upsert(
            LocalFileEntity(e.episode.id.value, DownloadState.DONE, file.absolutePath, pinned = pinned, downloadedAt = now),
        )
        return file
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun syncEnabledProgramEnqueuesTheLatestNUnpinned() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = 2))

        val result = repo.refresh()

        assertEquals(2, result.enqueued)
        val rows = episodes()
        assertEquals(listOf("2026-09-03", "2026-09-02"), rows.filter { it.localFile != null }.map { it.episode.title })
        assertTrue(rows.filter { it.localFile != null }.all { it.localFile?.state == DownloadState.PENDING && it.localFile?.pinned == false })
        assertNull(rows.first { it.episode.title == "2026-09-01" }.localFile)
    }

    @Test
    fun aNewerEpisodePushesTheOldestOutAndKeepsItsPlaybackState() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = 3))
        val file1 = markDone("a1", pinned = false)
        markDone("a2", pinned = false)
        markDone("a3", pinned = false)
        playback.update(episode("a1").episode.id) { PlaybackRules.advance(it, 10.minutes, 60.minutes, now) }

        gateway.snapshot = threeEpisodes.copy(episodes = threeEpisodes.episodes + se("a4", "2026-09-04"))
        val result = repo.refresh()

        assertEquals(1, result.enqueued)
        assertEquals(1, result.deleted)
        assertEquals(0, result.removed)
        assertFalse(file1.exists())
        val e1 = episode("a1")
        assertNull(e1.localFile, "the file row is gone")
        assertEquals(10.minutes, e1.playback?.position, "playback state survives a retention delete (ADR 0002)")
        assertEquals(DownloadState.PENDING, episode("a4").localFile?.state)
    }

    @Test
    fun pinnedEpisodesAreKeptAndNotCounted() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = 1))
        val pinnedFile = markDone("a1", pinned = true)

        val result = repo.refresh()

        assertEquals(1, result.enqueued, "the latest one is downloaded in addition to the pinned one")
        assertEquals(0, result.deleted)
        assertTrue(pinnedFile.exists())
        assertEquals(DownloadState.PENDING, episode("a3").localFile?.state)
    }

    @Test
    fun deleteAfterPlayedRemovesPlayedFilesOnSync() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = null, deleteAfterPlayed = true))
        val file3 = markDone("a3", pinned = false)
        playback.update(episode("a3").episode.id) { PlaybackRules.setPlayed(it, true, now) }

        val result = repo.refresh()

        assertEquals(1, result.deleted)
        assertEquals(2, result.enqueued, "played episodes are not downloaded again")
        assertFalse(file3.exists())
        assertEquals(true, episode("a3").playback?.played)
    }

    @Test
    fun syncDisabledDeletesUnpinnedFilesButKeepsPinned() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        val unpinned = markDone("a1", pinned = false)
        val pinned = markDone("a2", pinned = true)

        val result = repo.refresh()

        assertEquals(1, result.deleted)
        assertEquals(0, result.enqueued)
        assertFalse(unpinned.exists())
        assertTrue(pinned.exists())
    }

    @Test
    fun episodesGoneFromTheServerAreRemovedEvenWhenPinned() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        val pinned = markDone("a1", pinned = true)
        playback.update(episode("a1").episode.id) { PlaybackRules.advance(it, 10.minutes, 60.minutes, now) }

        gateway.snapshot = threeEpisodes.copy(episodes = threeEpisodes.episodes.drop(1))
        val result = repo.refresh()

        assertEquals(1, result.removed)
        assertFalse(pinned.exists())
        val titles = episodes().map { it.episode.title }
        assertEquals(listOf("2026-09-03", "2026-09-02"), titles)
        assertEquals(2, library.observePrograms().first().single().episodeCount)
    }

    @Test
    fun aGoneProgramIsOnHoldAndUntouched() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = 1))
        val file = markDone("a1", pinned = false)

        gateway.snapshot = ServerSnapshot(listOf(albumB), listOf(se("b1", "2026-09-05", program = "album-2")))
        val result = repo.refresh()

        assertEquals(1, result.onHold)
        assertEquals(0, result.deleted)
        assertEquals(0, result.removed)
        assertTrue(file.exists())
        assertEquals(3, library.observePrograms().first().first { it.program.name == "番組 A" }.episodeCount)
    }

    @Test
    fun theEpisodeBeingPlayedIsNotDeletedThisTime() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        val playing = markDone("a1", pinned = false)
        val other = markDone("a2", pinned = false)

        val result = repo.refresh(excluded = setOf(episode("a1").episode.id))

        assertEquals(1, result.deleted)
        assertTrue(playing.exists())
        assertFalse(other.exists())
    }

    @Test
    fun aQueuedRowThatFallsOutOfTheRuleIsCancelled() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = 3))
        repo.refresh()
        assertEquals(3, episodes().count { it.localFile?.state == DownloadState.PENDING })
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = 1))

        val result = repo.refresh()

        assertEquals(2, result.deleted)
        assertEquals(listOf("2026-09-03"), episodes().filter { it.localFile != null }.map { it.episode.title })
    }

    @Test
    fun downloadsAreEnqueuedNewestFirstAcrossPrograms() = runTest {
        signInAndSelect()
        gateway.snapshot = ServerSnapshot(
            programs = listOf(albumA, albumB),
            episodes = listOf(se("a1", "2026-09-01"), se("b2", "2026-09-02", "album-2"), se("a3", "2026-09-03"), se("b4", "2026-09-04", "album-2")),
        )
        repo.refresh()
        for (p in library.observePrograms().first()) library.updateSync(p.program.id, true, RetentionRule())

        repo.refresh()

        val order = ArrayList<String>()
        while (true) {
            val next = downloads.nextPending() ?: break
            order += next.episode.title
            downloads.markRunning(next.episode.id)
        }
        assertEquals(listOf("2026-09-04", "2026-09-03", "2026-09-02", "2026-09-01"), order)
    }

    @Test
    fun manualDownloadsComeBeforeSyncedOnes() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = 2))
        repo.refresh()
        now += 1.minutes
        downloads.enqueue(episode("a1").episode.id)

        assertEquals("2026-09-01", downloads.nextPending()?.episode?.title)
    }

    @Test
    fun manualDownloadPinsAnExistingSyncedRow() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = 1))
        repo.refresh()
        val e3 = episode("a3")
        assertEquals(false, e3.localFile?.pinned)

        downloads.enqueue(e3.episode.id)

        assertEquals(true, episode("a3").localFile?.pinned)
    }

    @Test
    fun refreshProgramTakesInWithoutDeletingOrTouchingLastSync() = runTest {
        signInAndSelect()
        gateway.snapshot = threeEpisodes
        repo.refresh()
        library.updateSync(programId(), syncEnabled = true, RetentionRule(keepLatest = 1))
        val file = markDone("a1", pinned = false)
        val fetchedAt = assertIs<SessionState.Ready>(session.state.first()).lastFetchedAt
        now += 5.minutes
        gateway.snapshot = threeEpisodes.copy(episodes = listOf(se("a4", "2026-09-04")))

        val result = assertNotNull(repo.refreshProgram(programId()))

        assertEquals(listOf(ServerItemId("album-1")), gateway.fetchedPrograms)
        assertEquals(1, result.episodes)
        assertEquals(0, result.enqueued)
        assertEquals(0, result.deleted)
        assertTrue(file.exists(), "a per-program refresh never deletes (ADR 0004)")
        assertEquals(4, library.observePrograms().first().single().episodeCount, "the new episode is added, the missing ones stay")
        assertNull(episode("a4").localFile, "nothing is enqueued")
        assertEquals(fetchedAt, assertIs<SessionState.Ready>(session.state.first()).lastFetchedAt)
    }

    @Test
    fun refreshProgramReturnsNullForAProgramWithoutServerId() = runTest {
        signInAndSelect()
        val id = ProgramId(db.programDao().insert(dev.tseki.jellyfinradio.data.db.ProgramEntity(serverItemId = null, name = "seed", stationName = null)))

        assertNull(repo.refreshProgram(id))
        assertTrue(gateway.fetchedPrograms.isEmpty())
    }
}
