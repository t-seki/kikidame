package dev.tseki.jellyfinradio.sync

import app.cash.turbine.test
import dev.tseki.jellyfinradio.domain.DownloadRepository
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.LibraryRefreshRepository
import dev.tseki.jellyfinradio.domain.LocalDeletionScope
import dev.tseki.jellyfinradio.domain.LibraryView
import dev.tseki.jellyfinradio.domain.RefreshResult
import dev.tseki.jellyfinradio.domain.ServerException
import dev.tseki.jellyfinradio.domain.SessionRepository
import dev.tseki.jellyfinradio.domain.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRefresherTest {
    private val now = Instant.parse("2026-09-17T00:00:00Z")

    private class FakeRefreshRepository : LibraryRefreshRepository {
        var result: RefreshResult? = null
        var error: ServerException? = null
        var gate: CompletableDeferred<Unit>? = null
        var calls = 0
        override suspend fun refresh(): RefreshResult {
            calls++
            gate?.await()
            error?.let { throw it }
            return result!!
        }
    }

    private class FakeDownloads(var missing: Int = 0) : DownloadRepository {
        override suspend fun enqueue(episodeId: EpisodeId) = Unit
        override suspend fun cancel(episodeId: EpisodeId) = Unit
        override suspend fun retry(episodeId: EpisodeId) = Unit
        override suspend fun unpin(episodeId: EpisodeId) = Unit
        override suspend fun deleteLocal(episodeId: EpisodeId) = LocalDeletionScope.FILE_ONLY
        override suspend fun reconcileMissingFiles(): Int = missing
        override suspend fun ensureFilePresent(episodeId: EpisodeId): Boolean = true
    }
    private class FakeSessionRepository : SessionRepository {
        val stateFlow = MutableStateFlow<SessionState>(SessionState.SignedOut(null, null))
        var signedOut = false
        override val state: Flow<SessionState> = stateFlow
        override suspend fun signIn(serverUrl: String, userName: String, password: String) = Unit
        override suspend fun listLibraries(): List<LibraryView> = emptyList()
        override suspend fun selectLibrary(library: LibraryView) = Unit
        override suspend fun signOut() {
            signedOut = true
        }
    }

    @Test
    fun successEmitsCountsMessage() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { result = RefreshResult(3, 40, 1, 6, now) }
        val session = FakeSessionRepository()
        val refresher = LibraryRefresher(repo, session, FakeDownloads(), this)

        refresher.messages.test {
            assertEquals(3, refresher.refresh()?.programs)
            assertEquals("番組 3 / 各回 40 を取得しました", awaitItem())
        }
        assertFalse(refresher.isRefreshing.value)
    }

    @Test
    fun reconcileMessageComesBeforeTheResult() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { result = RefreshResult(1, 2, 0, 0, now) }
        val refresher = LibraryRefresher(repo, FakeSessionRepository(), FakeDownloads(missing = 2), this)
        refresher.messages.test {
            refresher.refresh()
            assertTrue(awaitItem().contains("2 回"))
            assertEquals("番組 1 / 各回 2 を取得しました", awaitItem())
        }
    }
    @Test
    fun unauthorizedSignsOut() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { error = ServerException.Unauthorized() }
        val session = FakeSessionRepository()
        val refresher = LibraryRefresher(repo, session, FakeDownloads(), this)

        refresher.messages.test {
            assertNull(refresher.refresh())
            assertTrue(awaitItem().contains("ログイン"))
        }
        assertTrue(session.signedOut)
    }

    @Test
    fun unreachableKeepsSessionAndReports() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { error = ServerException.Unreachable() }
        val session = FakeSessionRepository()
        val refresher = LibraryRefresher(repo, session, FakeDownloads(), this)

        refresher.messages.test {
            assertNull(refresher.refresh())
            assertTrue(awaitItem().contains("接続できません"))
        }
        assertFalse(session.signedOut)
    }

    @Test
    fun concurrentRefreshIsIgnoredWhileOneIsRunning() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRefreshRepository().apply { result = RefreshResult(1, 1, 0, 0, now); this.gate = gate }
        val refresher = LibraryRefresher(repo, FakeSessionRepository(), FakeDownloads(), this)

        val first = async { refresher.refresh() }
        runCurrent()
        assertTrue(refresher.isRefreshing.value)
        assertNull(refresher.refresh(), "second call returns immediately")
        gate.complete(Unit)
        assertEquals(1, first.await()?.programs)
        assertEquals(1, repo.calls)
        assertFalse(refresher.isRefreshing.value)
    }
}
