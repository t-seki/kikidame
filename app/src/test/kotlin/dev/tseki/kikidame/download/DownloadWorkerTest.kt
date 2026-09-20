package dev.tseki.kikidame.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.tseki.kikidame.data.download.EpisodeDownloader
import dev.tseki.kikidame.data.jellyfin.DownloadStream
import dev.tseki.kikidame.data.jellyfin.JellyfinGateway
import dev.tseki.kikidame.data.jellyfin.ServerCredentials
import dev.tseki.kikidame.domain.DownloadQueue
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.Episode
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.domain.LocalFile
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.ServerEpisode
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.ServerItemId
import dev.tseki.kikidame.domain.ServerProgram
import dev.tseki.kikidame.domain.ServerSnapshot
import dev.tseki.kikidame.domain.Session
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class DownloadWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** PENDING の一覧をメモリで持つキュー。 */
    private class MemoryQueue(items: List<EpisodeWithState>) : DownloadQueue {
        val states = items.associate { it.episode.id to it }.toMutableMap()
        val state = items.associate { it.episode.id to DownloadState.PENDING }.toMutableMap()
        val done = ArrayList<EpisodeId>()

        override suspend fun nextPending(): EpisodeWithState? =
            state.entries.firstOrNull { it.value == DownloadState.PENDING }?.let { states.getValue(it.key) }

        override suspend fun requeueFailed(maxAttempts: Int) = Unit
        override suspend fun resetRunning() = Unit
        override suspend fun resetToPending(episodeId: EpisodeId) { state[episodeId] = DownloadState.PENDING }
        override suspend fun markRunning(episodeId: EpisodeId) { state[episodeId] = DownloadState.RUNNING }
        override suspend fun markDone(episodeId: EpisodeId, path: String, sizeBytes: Long) { state[episodeId] = DownloadState.DONE; done += episodeId }
        override suspend fun markFailed(episodeId: EpisodeId) { state[episodeId] = DownloadState.FAILED }
        override suspend fun isStillWanted(episodeId: EpisodeId): Boolean = state[episodeId] != DownloadState.DONE
    }

    private class Gateway(val files: Map<ServerItemId, ByteArray>, val unauthorized: Boolean = false) : JellyfinGateway {
        override suspend fun signIn(serverUrl: String, userName: String, password: String) = error("unused")
        override suspend fun listLibraries(credentials: ServerCredentials): List<LibraryView> = error("unused")
        override suspend fun fetchLibrary(credentials: ServerCredentials, libraryId: ServerItemId): ServerSnapshot = error("unused")
        override suspend fun fetchProgramEpisodes(credentials: ServerCredentials, programServerId: ServerItemId): List<ServerEpisode> = error("unused")
        override suspend fun fetchProgram(credentials: ServerCredentials, programServerId: ServerItemId): ServerProgram? = error("unused")
        override suspend fun openDownload(credentials: ServerCredentials, episodeServerId: ServerItemId, rangeStart: Long): DownloadStream {
            if (unauthorized) throw ServerException.Unauthorized()
            val bytes = files[episodeServerId] ?: throw ServerException.Failed("HTTP 404")
            return DownloadStream(null, bytes.size.toLong(), bytes.inputStream())
        }
    }

    private class Sessions(initial: SessionState) : SessionRepository {
        val flow = MutableStateFlow(initial)
        var signedOut = false
        override val state: Flow<SessionState> = flow
        override suspend fun signIn(serverUrl: String, userName: String, password: String) = Unit
        override suspend fun listLibraries(): List<LibraryView> = emptyList()
        override suspend fun selectLibrary(library: LibraryView) = Unit
        override suspend fun signOut() { signedOut = true; flow.value = SessionState.SignedOut(null, null) }
    }

    private val ready = SessionState.Ready(Session("https://x/", "u", "uid", "tok"), SelectedLibrary(ServerItemId("lib"), "Radio"), null)

    private fun item(id: Long, server: String?) = EpisodeWithState(
        episode = Episode(EpisodeId(id), server?.let(::ServerItemId), ProgramId(1), "t$id", Instant.parse("2026-09-15T15:00:00Z"), null, 30.minutes, 0, "m4a"),
        localFile = LocalFile(EpisodeId(id), DownloadState.PENDING, File(tmp.root, "s/p/t$id.m4a").absolutePath, pinned = true),
        playback = null,
    )

    private fun build(queue: DownloadQueue, gateway: JellyfinGateway, sessions: SessionRepository): DownloadWorker =
        TestListenableWorkerBuilder<DownloadWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    DownloadWorker(appContext, workerParameters, queue, EpisodeDownloader(gateway, queue), sessions)
            })
            .build()

    @Test
    fun drainsTheQueueAndContinuesPastFailures() = runTest {
        val queue = MemoryQueue(listOf(item(1, "a1"), item(2, "missing"), item(3, "a3")))
        val gateway = Gateway(mapOf(ServerItemId("a1") to ByteArray(10) { 1 }, ServerItemId("a3") to ByteArray(20) { 3 }))

        val result = build(queue, gateway, Sessions(ready)).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(EpisodeId(1), EpisodeId(3)), queue.done)
        assertEquals(DownloadState.FAILED, queue.state[EpisodeId(2)])
        assertTrue(File(tmp.root, "s/p/t1.m4a").isFile && File(tmp.root, "s/p/t3.m4a").isFile)
    }

    @Test
    fun signedOutSessionDoesNothing() = runTest {
        val queue = MemoryQueue(listOf(item(1, "a1")))
        build(queue, Gateway(emptyMap()), Sessions(SessionState.SignedOut(null, null))).doWork()
        assertEquals(DownloadState.PENDING, queue.state[EpisodeId(1)])
    }

    @Test
    fun unauthorizedSignsOutAndStops() = runTest {
        val queue = MemoryQueue(listOf(item(1, "a1"), item(2, "a2")))
        val sessions = Sessions(ready)
        val result = build(queue, Gateway(emptyMap(), unauthorized = true), sessions).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(sessions.signedOut)
        assertEquals(DownloadState.PENDING, queue.state[EpisodeId(1)], "row goes back to PENDING for next time")
        assertEquals(DownloadState.PENDING, queue.state[EpisodeId(2)])
    }
}
