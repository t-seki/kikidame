package dev.tseki.kikidame.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.data.files.EpisodesDirectory
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.ServerEpisode
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.ServerItemId
import dev.tseki.kikidame.domain.ServerProgram
import dev.tseki.kikidame.domain.ServerSnapshot
import dev.tseki.kikidame.domain.SessionState
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class RoomLibraryRefreshRepositoryTest : RoomTestBase() {
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

    private val program = "パンサー向井のふらっと"
    private val station = "TBSラジオ"

    private fun serverEpisode(id: String, title: String, aired: String, runtime: Int = 90) = ServerEpisode(
        serverId = ServerItemId(id),
        programServerId = ServerItemId("album-1"),
        title = title,
        airedAt = Instant.parse(aired),
        addedAt = Instant.parse("2026-09-16T02:00:00Z"),
        runtime = runtime.minutes,
        sizeBytes = null,
        container = "m4a",
    )

    private val snapshot = ServerSnapshot(
        programs = listOf(ServerProgram(ServerItemId("album-1"), program, station)),
        episodes = listOf(
            serverEpisode("audio-1", "$program 2026-09-14-1", "2026-09-13T15:00:00Z"),
            serverEpisode("audio-2", "$program 2026-09-14-2", "2026-09-13T15:00:00Z", runtime = 60),
            serverEpisode("audio-3", "$program 2026-09-17-1", "2026-09-16T15:00:00Z"),
        ),
    )

    private suspend fun signInAndSelect() {
        session.signIn("jellyfin.lab.example", "alice", "secret")
        session.selectLibrary(LibraryView(ServerItemId("lib-1"), "Radio", "music", isMusic = true))
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun refreshRequiresASelectedLibrary() = runTest {
        assertFailsWith<ServerException.Unauthorized> { repo.refresh() }
        session.signIn("jellyfin.lab.example", "alice", "secret")
        assertFailsWith<ServerException.Unauthorized> { repo.refresh() }
    }

    @Test
    fun linksSeededRowsAndAddsServerOnlyEpisodes() = runTest {
        seed(
            listOf(
                scanned(station = station, program = program, title = "$program 2026-09-14-1", airedAt = "2026-09-13T15:00:00Z"),
                scanned(station = station, program = program, title = "$program 2026-09-14-2", airedAt = "2026-09-13T15:00:00Z"),
            ),
        )
        val seeded = library.observePrograms().first().single()
        val listened = library.observeEpisodes(seeded.program.id).first().first { it.episode.title.endsWith("-2") }
        playback.update(listened.episode.id) { PlaybackRules.advance(it, 24.minutes, 60.minutes, now) }
        signInAndSelect()
        gateway.snapshot = snapshot

        val result = repo.refresh()

        assertEquals(1, result.linkedPrograms)
        assertEquals(2, result.linkedEpisodes)
        assertEquals(listOf(ServerItemId("lib-1")), gateway.fetchedLibraries)

        val programs = library.observePrograms().first()
        assertEquals(1, programs.size, "the seeded program must not be duplicated")
        val summary = programs.single()
        assertEquals("album-1", summary.program.serverItemId?.value)
        assertEquals(3, summary.episodeCount)
        assertEquals(2, summary.localEpisodeCount)

        val episodes = library.observeEpisodes(summary.program.id).first()
        assertEquals(listOf("$program 2026-09-17-1", "$program 2026-09-14-1", "$program 2026-09-14-2"), episodes.map { it.episode.title })
        val serverOnly = episodes.first()
        assertEquals("audio-3", serverOnly.episode.serverItemId?.value)
        assertNull(serverOnly.localFile)
        assertTrue(!serverOnly.isPlayable)

        val relinked = episodes.first { it.episode.title.endsWith("-2") }
        assertEquals("audio-2", relinked.episode.serverItemId?.value)
        assertEquals(24.minutes, relinked.playback?.position, "playback state survives matching")
        assertEquals(60.minutes, relinked.episode.runtime, "server metadata overwrites the seeded values")
        assertEquals(1024, relinked.episode.sizeBytes, "a value the server does not send keeps the seeded one")
        assertEquals(Instant.parse("2026-09-16T02:00:00Z"), relinked.episode.addedAt)
        assertTrue(relinked.localFile?.pinned == true)

        assertIs<SessionState.Ready>(session.state.first()).let { assertEquals(now, it.lastFetchedAt) }
    }

    @Test
    fun secondRefreshOverwritesServerRowsWithoutDuplicating() = runTest {
        signInAndSelect()
        gateway.snapshot = snapshot
        repo.refresh()

        gateway.snapshot = snapshot.copy(
            programs = listOf(ServerProgram(ServerItemId("album-1"), "$program（改）", station)),
            episodes = snapshot.episodes.map { if (it.serverId.value == "audio-3") it.copy(title = "renamed") else it },
        )
        val result = repo.refresh()

        assertEquals(0, result.linkedPrograms)
        val summary = library.observePrograms().first().single()
        assertEquals("$program（改）", summary.program.name)
        assertEquals(3, summary.episodeCount)
        assertTrue(library.observeEpisodes(summary.program.id).first().any { it.episode.title == "renamed" })
    }

    /** 出演者（#70）: 取り込みで各回に入り、次の取り込みでサーバの値で上書きされる（サーバに無くなれば空になる）。 */
    @Test
    fun performersAreStoredAndOverwrittenBySync() = runTest {
        signInAndSelect()
        gateway.snapshot = snapshot.copy(
            episodes = snapshot.episodes.map {
                when (it.serverId.value) {
                    "audio-1" -> it.copy(performers = listOf("向井慧", "ゲスト A"))
                    else -> it
                }
            },
        )
        repo.refresh()
        val programId = library.observePrograms().first().single().program.id
        fun List<EpisodeWithState>.performersOf(id: String) =
            first { it.episode.serverItemId?.value == id }.episode.performers
        var episodes = library.observeEpisodes(programId).first()
        assertEquals(listOf("向井慧", "ゲスト A"), episodes.performersOf("audio-1"))
        assertEquals(emptyList(), episodes.performersOf("audio-2"))

        gateway.snapshot = snapshot.copy(
            episodes = snapshot.episodes.map {
                when (it.serverId.value) {
                    "audio-1" -> it.copy(performers = emptyList())
                    "audio-2" -> it.copy(performers = listOf("向井慧"))
                    else -> it
                }
            },
        )
        repo.refresh()
        episodes = library.observeEpisodes(programId).first()
        assertEquals(emptyList(), episodes.performersOf("audio-1"))
        assertEquals(listOf("向井慧"), episodes.performersOf("audio-2"))
    }

    /** 番組ごとサーバの一覧から消えたら消失 = 判断保留（各回が消えた場合は RoomSyncTest）。 */
    @Test
    fun aProgramGoneFromTheServerIsLeftAlone() = runTest {
        signInAndSelect()
        gateway.snapshot = snapshot
        repo.refresh()

        gateway.snapshot = ServerSnapshot(emptyList(), emptyList())
        repo.refresh()

        assertEquals(3, library.observePrograms().first().single().episodeCount)
    }

    @Test
    fun unauthorizedPropagatesAndLeavesDataUntouched() = runTest {
        signInAndSelect()
        gateway.snapshot = snapshot
        repo.refresh()
        gateway.failWith = ServerException.Unauthorized()

        assertFailsWith<ServerException.Unauthorized> { repo.refresh() }
        assertEquals(1, library.observePrograms().first().size)
    }

    /** 「別のサーバに接続」は行・セッションに加えて音声ファイルも消す（#50。残しても到達する手段が無い）。 */
    @Test
    fun resetAllClearsRowsSessionAndFiles() = runTest {
        signInAndSelect()
        gateway.snapshot = snapshot
        repo.refresh()
        val directory = EpisodesDirectory(ApplicationProvider.getApplicationContext())
        val file = directory.resolve("TBS/X/X 2026-06-12.m4a").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(16)) }
        assertTrue(file.isFile)
        RoomLocalDataReset(db, store, directory).resetAll()
        assertTrue(library.observePrograms().first().isEmpty())
        assertIs<SessionState.SignedOut>(session.state.first())
        assertFalse(file.exists())
        assertTrue(directory.root!!.listFiles().isNullOrEmpty())
    }
}
