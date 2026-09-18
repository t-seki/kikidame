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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** 再取り込み（サーバ ID の変更）後の結び直し（ADR 0005）と、タイトルが違う未結合の各回の第二段突合（放送日 + 尺）。 */
@RunWith(AndroidJUnit4::class)
class RoomRematchTest : RoomTestBase() {
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

    private val station = "TBSラジオ"
    private val programName = "パンサー向井のふらっと"

    private fun se(id: String, program: String, title: String, aired: String, runtime: Duration = 90.minutes) = ServerEpisode(
        serverId = ServerItemId(id),
        programServerId = ServerItemId(program),
        title = title,
        airedAt = Instant.parse(if ('T' in aired) aired else "${aired}T00:00:00Z"),
        addedAt = Instant.parse("2026-09-16T02:00:00Z"),
        runtime = runtime,
        sizeBytes = null,
        container = "m4a",
    )

    private fun snapshot(program: String, vararg episodes: ServerEpisode) =
        ServerSnapshot(listOf(ServerProgram(ServerItemId(program), programName, station)), episodes.toList())

    private val original = snapshot("P1", se("E1", "P1", "2026-09-01", "2026-09-01"), se("E2", "P1", "2026-09-02", "2026-09-02"), se("E3", "P1", "2026-09-03", "2026-09-03"))

    private suspend fun signInAndSelect() {
        session.signIn("jellyfin.lab.example", "alice", "secret")
        session.selectLibrary(LibraryView(ServerItemId("lib-1"), "Radio", "music", isMusic = true))
    }

    private suspend fun programs() = library.observePrograms().first()

    private suspend fun episodes(programId: ProgramId): List<EpisodeWithState> = library.observeEpisodes(programId).first()

    private suspend fun episode(title: String): EpisodeWithState =
        programs().flatMap { episodes(it.program.id) }.first { it.episode.title == title }

    private suspend fun markDone(title: String, pinned: Boolean): File {
        val e = episode(title)
        val file = File(directory.root, "${e.episode.id.value}.m4a").apply { parentFile?.mkdirs(); writeBytes(ByteArray(8)) }
        db.localFileDao().upsert(LocalFileEntity(e.episode.id.value, DownloadState.DONE, file.absolutePath, pinned = pinned, downloadedAt = now))
        return file
    }

    @After
    fun tearDown() = scope.cancel()

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
            se("N1", "P9", "2026-09-01", "2026-09-01"), se("N2", "P9", "2026-09-02", "2026-09-02"),
            se("N3", "P9", "2026-09-03", "2026-09-03"), se("N4", "P9", "2026-09-04", "2026-09-04"),
        )
        val result = repo.refresh()

        assertEquals(1, programs().size, "no duplicate program row")
        assertEquals("P9", programs().single().program.serverItemId?.value)
        assertEquals(0, result.removed)
        assertEquals(0, result.onHold)
        val relinked = episode("2026-09-02")
        assertEquals(e2, relinked.episode.id, "same local row")
        assertEquals("N2", relinked.episode.serverItemId?.value)
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

        gateway.snapshot = snapshot("P1", se("N1", "P1", "2026-09-01", "2026-09-01"), se("N2", "P1", "2026-09-02", "2026-09-02"), se("N3", "P1", "2026-09-03", "2026-09-03"))
        val result = repo.refresh()

        assertEquals(0, result.removed)
        assertTrue(pinned.exists())
        assertEquals(e1, episode("2026-09-01").episode.id)
        assertEquals("N1", episode("2026-09-01").episode.serverItemId?.value)
        assertEquals(3, episodes(programId).size)
    }

    @Test
    fun anEpisodeThatReallyVanishedIsStillRemovedAfterRelinking() = runTest {
        signInAndSelect()
        gateway.snapshot = original
        repo.refresh()
        val programId = programs().single().program.id
        val goneFile = markDone("2026-09-03", pinned = true)

        gateway.snapshot = snapshot("P1", se("N1", "P1", "2026-09-01", "2026-09-01"), se("N2", "P1", "2026-09-02", "2026-09-02"))
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

        gateway.snapshot = snapshot("P1", se("N1", "P1", "2026-09-01", "2026-09-01"), se("N2", "P1", "2026-09-02", "2026-09-02"), se("N3", "P1", "2026-09-03", "2026-09-03"))
        val result = assertNotNull(repo.syncProgram(programId))

        assertEquals(0, result.removed)
        assertTrue(file.exists())
        assertEquals("N1", episode("2026-09-01").episode.serverItemId?.value)
    }

    @Test
    fun unlinkedEpisodesLinkByAiredDayAndRuntimeWhenTitlesDiffer() = runTest {
        seed(
            listOf(
                scanned(station = station, program = programName, title = "$programName 2026-09-16-1", airedAt = "2026-09-15T15:00:00Z", runtime = 89.minutes + 59.seconds),
                scanned(station = station, program = programName, title = "$programName 2026-09-16-2", airedAt = "2026-09-15T15:00:00Z", runtime = 60.minutes + 5.seconds),
            ),
        )
        val seeded = programs().single()
        val listened = episodes(seeded.program.id).first { it.episode.title.endsWith("-2") }
        playback.update(listened.episode.id) { PlaybackRules.advance(it, 20.minutes, 60.minutes, now) }
        signInAndSelect()
        gateway.snapshot = snapshot(
            "P1",
            // 手元の行の放送日（JST 0 時 = 前日 15:00Z）と同じ日
            se("E1", "P1", "2026-09-16 (1)", "2026-09-15T15:00:00Z", runtime = 90.minutes),
            se("E2", "P1", "2026-09-16 (2)", "2026-09-15T15:00:00Z", runtime = 60.minutes + 4.seconds),
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
