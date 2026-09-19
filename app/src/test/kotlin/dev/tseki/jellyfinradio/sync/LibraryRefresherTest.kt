package dev.tseki.jellyfinradio.sync

import app.cash.turbine.test
import dev.tseki.jellyfinradio.domain.AppSettingsRepository
import dev.tseki.jellyfinradio.domain.ThemeMode
import dev.tseki.jellyfinradio.domain.DownloadRepository
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.LibraryRefreshRepository
import dev.tseki.jellyfinradio.domain.LibraryView
import dev.tseki.jellyfinradio.domain.LocalDeletionScope
import dev.tseki.jellyfinradio.domain.ProgramId
import dev.tseki.jellyfinradio.domain.RefreshResult
import dev.tseki.jellyfinradio.domain.SelectedLibrary
import dev.tseki.jellyfinradio.domain.ServerException
import dev.tseki.jellyfinradio.domain.ServerItemId
import dev.tseki.jellyfinradio.domain.Session
import dev.tseki.jellyfinradio.domain.SessionRepository
import dev.tseki.jellyfinradio.domain.SessionState
import dev.tseki.jellyfinradio.download.DownloadKicker
import dev.tseki.jellyfinradio.playback.NowPlaying
import dev.tseki.jellyfinradio.playback.NowPlayingState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
        var programResult: RefreshResult? = null
        var error: ServerException? = null
        var gate: CompletableDeferred<Unit>? = null
        var calls = 0
        var programCalls = 0
        var lastExcluded: Set<EpisodeId> = emptySet()
        override suspend fun refresh(excluded: Set<EpisodeId>): RefreshResult {
            calls++
            lastExcluded = excluded
            gate?.await()
            error?.let { throw it }
            return result!!
        }

        override suspend fun refreshProgram(programId: ProgramId): RefreshResult? {
            programCalls++
            error?.let { throw it }
            return programResult
        }

        var syncProgramResult: RefreshResult? = null
        var syncProgramCalls = 0
        override suspend fun syncProgram(programId: ProgramId, excluded: Set<EpisodeId>): RefreshResult? {
            syncProgramCalls++
            lastExcluded = excluded
            error?.let { throw it }
            return syncProgramResult
        }
    }

    private class FakeDownloads(var missing: Int = 0) : DownloadRepository {
        override suspend fun enqueue(episodeId: EpisodeId) = Unit
        override suspend fun enqueueForSync(episodeIds: List<EpisodeId>) = episodeIds.size
        override suspend fun removeEpisode(episodeId: EpisodeId) = Unit
        override suspend fun removeProgram(programId: ProgramId) = Unit
        override suspend fun cancel(episodeId: EpisodeId) = Unit
        override suspend fun retry(episodeId: EpisodeId) = Unit
        override suspend fun unpin(episodeId: EpisodeId) = Unit
        override suspend fun deleteLocal(episodeId: EpisodeId) = LocalDeletionScope.FILE_ONLY
        override suspend fun reconcileMissingFiles(): Int = missing
        override suspend fun ensureFilePresent(episodeId: EpisodeId): Boolean = true
    }

    private class FakeSessionRepository(ready: Boolean = true) : SessionRepository {
        val stateFlow = MutableStateFlow<SessionState>(
            if (ready) {
                SessionState.Ready(Session("https://s", "alice", "u", "t"), SelectedLibrary(ServerItemId("lib"), "Radio"), null)
            } else {
                SessionState.SignedOut(null, null)
            },
        )
        var signedOut = false
        override val state: Flow<SessionState> = stateFlow
        override suspend fun signIn(serverUrl: String, userName: String, password: String) = Unit
        override suspend fun listLibraries(): List<LibraryView> = emptyList()
        override suspend fun selectLibrary(library: LibraryView) = Unit
        override suspend fun signOut() {
            signedOut = true
        }
    }

    private class FakeSettings(wifiOnly: Boolean = true) : AppSettingsRepository {
        override val wifiOnly = MutableStateFlow(wifiOnly)
        override suspend fun setWifiOnly(value: Boolean) {
            wifiOnly.value = value
        }
        override val playbackSpeed = MutableStateFlow(1.0f)
        override suspend fun setPlaybackSpeed(value: Float) {
            playbackSpeed.value = value
        }
        override val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
        override suspend fun setThemeMode(value: ThemeMode) {
            themeMode.value = value
        }
    }

    private class FakeNetwork(var metered: Boolean = false) : NetworkStatus {
        override fun isMetered() = metered
    }

    private class FakeKicker : DownloadKicker {
        var kicks = 0
        override suspend fun kick() {
            kicks++
        }
    }

    private fun CoroutineScope.refresher(
        repo: FakeRefreshRepository,
        session: FakeSessionRepository = FakeSessionRepository(),
        downloads: FakeDownloads = FakeDownloads(),
        settings: FakeSettings = FakeSettings(),
        network: FakeNetwork = FakeNetwork(),
        nowPlaying: NowPlaying = NowPlaying(),
        kicker: FakeKicker = FakeKicker(),
    ) = LibraryRefresher(repo, session, downloads, settings, network, nowPlaying, kicker, this)

    private fun nowPlaying(episodeId: Long, ended: Boolean = false) =
        NowPlayingState(EpisodeId(episodeId), "title", "program", isPlaying = !ended, isEnded = ended)

    @Test
    fun successEmitsCountsMessage() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { result = RefreshResult(3, 40, 1, 6, now) }
        val refresher = refresher(repo)

        refresher.messages.test {
            assertEquals(3, refresher.refresh()?.programs)
            assertEquals("番組 3 / 各回 40 を取得", awaitItem())
        }
        assertFalse(refresher.isRefreshing.value)
    }

    @Test
    fun syncMessageListsWhatHappened() {
        assertEquals(
            "番組 3 / 各回 40 を取得。5 回をダウンロード予約、3 回を削除。1 番組はサーバ上で見つからず、そのままにしました",
            RefreshResult(3, 40, 0, 0, now, enqueued = 5, deleted = 2, removed = 1, onHold = 1).toSyncMessage(),
        )
        assertEquals("番組 3 / 各回 40 を取得。2 回を削除", RefreshResult(3, 40, 0, 0, now, deleted = 2).toSyncMessage())
    }

    @Test
    fun enqueuedDownloadsKickTheWorkerAndPassTheNowPlayingEpisode() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { result = RefreshResult(1, 1, 0, 0, now, enqueued = 2) }
        val kicker = FakeKicker()
        val nowPlaying = NowPlaying().apply { set(nowPlaying(42)) }
        val refresher = refresher(repo, kicker = kicker, nowPlaying = nowPlaying)

        refresher.refresh()

        assertEquals(1, kicker.kicks)
        assertEquals(setOf(EpisodeId(42)), repo.lastExcluded)
    }

    /** 聴き終えて止まっている回は除外しない（#27）。「再生済みなら削除」で次の同期に消える。 */
    @Test
    fun endedNowPlayingIsNotExcluded() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { result = RefreshResult(1, 1, 0, 0, now) }
        val nowPlaying = NowPlaying().apply { set(nowPlaying(42, ended = true)) }
        refresher(repo, nowPlaying = nowPlaying).refresh()
        assertEquals(emptySet(), repo.lastExcluded)
    }

    @Test
    fun nothingEnqueuedDoesNotKick() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { result = RefreshResult(1, 1, 0, 0, now) }
        val kicker = FakeKicker()
        refresher(repo, kicker = kicker).refresh()
        assertEquals(0, kicker.kicks)
    }

    @Test
    fun meteredNetworkWithWifiOnlySkipsTheServerButStillReconciles() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { result = RefreshResult(1, 1, 0, 0, now) }
        val refresher = refresher(repo, downloads = FakeDownloads(missing = 1), network = FakeNetwork(metered = true))

        refresher.messages.test {
            assertNull(refresher.refresh())
            assertTrue(awaitItem().contains("1 回"))
            assertEquals("Wi-Fi に接続していないため更新しません", awaitItem())
        }
        assertEquals(0, repo.calls)
    }

    @Test
    fun meteredNetworkIsFineWhenWifiOnlyIsOff() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { result = RefreshResult(1, 1, 0, 0, now) }
        val refresher = refresher(repo, settings = FakeSettings(wifiOnly = false), network = FakeNetwork(metered = true))
        assertEquals(1, refresher.refresh()?.programs)
    }

    @Test
    fun silentRefreshEmitsNoMessages() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { result = RefreshResult(1, 1, 0, 0, now) }
        val refresher = refresher(repo)
        refresher.messages.test {
            assertEquals(1, refresher.refresh(silent = true)?.programs)
            expectNoEvents()
        }
    }

    @Test
    fun notReadyReturnsNullWithoutTouchingAnything() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { result = RefreshResult(1, 1, 0, 0, now) }
        val session = FakeSessionRepository(ready = false)
        assertNull(refresher(repo, session = session).refresh())
        assertEquals(0, repo.calls)
        assertFalse(session.signedOut)
    }

    @Test
    fun syncProgramReportsAndKicks() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { syncProgramResult = RefreshResult(1, 6, 0, 0, now, enqueued = 2, deleted = 1) }
        val kicker = FakeKicker()
        val nowPlaying = NowPlaying().apply { set(nowPlaying(7)) }
        val refresher = refresher(repo, kicker = kicker, nowPlaying = nowPlaying)
        refresher.messages.test {
            assertEquals(2, refresher.syncProgram(ProgramId(1))?.enqueued)
            assertEquals("各回 6 を確認。2 回をダウンロード予約、1 回を削除", awaitItem())
        }
        assertEquals(1, kicker.kicks)
        assertEquals(setOf(EpisodeId(7)), repo.lastExcluded)
        assertEquals(0, repo.calls)
    }

    @Test
    fun programSyncMessages() {
        assertEquals("各回 6 を確認。手元は最新です", RefreshResult(1, 6, 0, 0, now).toProgramSyncMessage())
        assertEquals("この番組はサーバ上で見つかりません。何も変えていません", RefreshResult(0, 0, 0, 0, now, onHold = 1).toProgramSyncMessage())
    }

    @Test
    fun refreshProgramUsesTheProgramPathAndItsOwnMessage() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { programResult = RefreshResult(1, 7, 0, 0, now) }
        val refresher = refresher(repo)
        refresher.messages.test {
            assertEquals(7, refresher.refreshProgram(ProgramId(1))?.episodes)
            assertEquals("各回 7 を取得しました", awaitItem())
        }
        assertEquals(1, repo.programCalls)
        assertEquals(0, repo.calls)
    }

    @Test
    fun refreshProgramFallsBackToAFullSyncWithoutServerId() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { programResult = null; result = RefreshResult(2, 9, 0, 0, now) }
        val refresher = refresher(repo)
        refresher.messages.test {
            assertEquals(9, refresher.refreshProgram(ProgramId(1))?.episodes)
            assertEquals("番組 2 / 各回 9 を取得", awaitItem())
        }
        assertEquals(1, repo.calls)
    }

    @Test
    fun reconcileMessageComesBeforeTheResult() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { result = RefreshResult(1, 2, 0, 0, now) }
        val refresher = refresher(repo, downloads = FakeDownloads(missing = 2))
        refresher.messages.test {
            refresher.refresh()
            assertTrue(awaitItem().contains("2 回"))
            assertEquals("番組 1 / 各回 2 を取得", awaitItem())
        }
    }

    @Test
    fun unauthorizedSignsOut() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { error = ServerException.Unauthorized() }
        val session = FakeSessionRepository()
        val refresher = refresher(repo, session = session)

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
        val refresher = refresher(repo, session = session)

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
        val refresher = refresher(repo)

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
