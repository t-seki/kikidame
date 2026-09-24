package dev.tseki.kikidame.sync

import app.cash.turbine.test
import dev.tseki.kikidame.domain.AppSettingsRepository
import dev.tseki.kikidame.domain.ThemeMode
import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.LibraryRefreshRepository
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.domain.LocalDeletionScope
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.RefreshResult
import dev.tseki.kikidame.ui.UiText
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.ServerItemId
import dev.tseki.kikidame.domain.Session
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.download.DownloadKicker
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.playback.NowPlayingState
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
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun kick() {
            kicks++
            gate?.await()
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

    /** 手動で変化が無ければ「最新の状態です」。取得した件数は出さない（#142）。 */
    @Test
    fun manualRefreshWithoutChangesSaysUpToDate() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { result = RefreshResult(3, 40, 1, 6, now) }
        val refresher = refresher(repo)

        refresher.messages.test {
            assertEquals(3, refresher.refresh()?.programs)
            assertEquals(upToDate, awaitItem())
        }
        assertFalse(refresher.isRefreshing.value)
    }

    @Test
    fun syncMessageListsOnlyTheChanges() {
        // 「新しい回 4 件。5 回をダウンロード予約、3 回を削除。1 番組はサーバ上で見つからず、そのままにしました」
        assertEquals(
            sentences(
                UiText.Plural(R.plurals.sync_new_episodes, 4),
                actions(UiText.Plural(R.plurals.sync_enqueued, 5), UiText.Plural(R.plurals.sync_deleted, 3)),
                UiText.Plural(R.plurals.sync_on_hold, 1),
            ),
            RefreshResult(3, 40, 0, 0, now, enqueued = 5, deleted = 2, removed = 1, onHold = 1, newEpisodes = 4).toSyncMessage(),
        )
        // 「2 回を削除」
        assertEquals(
            sentences(actions(UiText.Plural(R.plurals.sync_deleted, 2))),
            RefreshResult(3, 40, 0, 0, now, deleted = 2).toSyncMessage(),
        )
        // 「新しい回 5 件」
        assertEquals(
            sentences(UiText.Plural(R.plurals.sync_new_episodes, 5)),
            RefreshResult(3, 40, 0, 0, now, newEpisodes = 5).toSyncMessage(),
        )
        // 判断保留は変化ではない: 「最新の状態です。1 番組はサーバ上で見つからず、そのままにしました」
        assertEquals(
            sentences(UiText.Res(R.string.sync_up_to_date), UiText.Plural(R.plurals.sync_on_hold, 1)),
            RefreshResult(3, 40, 0, 0, now, onHold = 1).toSyncMessage(),
        )
    }

    @Test
    fun changesAreNewEpisodesEnqueuedAndDeleted() {
        assertFalse(RefreshResult(3, 40, 2, 5, now, onHold = 1).hasChanges, "linked episodes and On Hold are not changes")
        assertTrue(RefreshResult(3, 40, 0, 0, now, newEpisodes = 1).hasChanges)
        assertTrue(RefreshResult(3, 40, 0, 0, now, enqueued = 1).hasChanges)
        assertTrue(RefreshResult(3, 40, 0, 0, now, deleted = 1).hasChanges)
        assertTrue(RefreshResult(3, 40, 0, 0, now, removed = 1).hasChanges)
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
            assertEquals(UiText.Plural(R.plurals.sync_reconciled, 1), awaitItem())
            assertEquals(UiText.Res(R.string.sync_not_on_wifi), awaitItem())
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
    fun silentRefreshWithoutChangesEmitsNoMessages() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { result = RefreshResult(1, 1, 0, 0, now) }
        val refresher = refresher(repo)
        refresher.messages.test {
            assertEquals(1, refresher.refresh(silent = true)?.programs)
            expectNoEvents()
        }
    }

    /** 判断保留だけでは裏の同期は何も出さない（変化ではない）。 */
    @Test
    fun silentRefreshWithOnlyOnHoldEmitsNoMessages() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { result = RefreshResult(1, 1, 0, 0, now, onHold = 1) }
        val refresher = refresher(repo)
        refresher.messages.test {
            assertEquals(1, refresher.refresh(silent = true)?.onHold)
            expectNoEvents()
        }
    }

    /** 裏の同期でも手元に変化があれば、手動と同じ文言を出す（#142）。 */
    @Test
    fun silentRefreshWithChangesReportsThem() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { result = RefreshResult(1, 9, 0, 0, now, enqueued = 2, newEpisodes = 3) }
        val refresher = refresher(repo)
        refresher.messages.test {
            assertEquals(3, refresher.refresh(silent = true)?.newEpisodes)
            assertEquals(
                sentences(UiText.Plural(R.plurals.sync_new_episodes, 3), actions(UiText.Plural(R.plurals.sync_enqueued, 2))),
                awaitItem(),
            )
            expectNoEvents()
        }
        assertFalse(refresher.isRefreshing.value, "still no spinner")
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
            assertEquals(
                sentences(actions(UiText.Plural(R.plurals.sync_enqueued, 2), UiText.Plural(R.plurals.sync_deleted, 1))),
                awaitItem(),
            )
        }
        assertEquals(1, kicker.kicks)
        assertEquals(setOf(EpisodeId(7)), repo.lastExcluded)
        assertEquals(0, repo.calls)
    }

    @Test
    fun programSyncMessages() {
        assertEquals(upToDate, RefreshResult(1, 6, 0, 0, now).toProgramSyncMessage())
        assertEquals(
            sentences(UiText.Plural(R.plurals.sync_new_episodes, 2), actions(UiText.Plural(R.plurals.sync_enqueued, 2))),
            RefreshResult(1, 6, 0, 0, now, enqueued = 2, newEpisodes = 2).toProgramSyncMessage(),
        )
        assertEquals(UiText.Res(R.string.sync_program_gone), RefreshResult(0, 0, 0, 0, now, onHold = 1).toProgramSyncMessage())
    }

    @Test
    fun refreshProgramUsesTheProgramPathAndItsOwnMessage() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { programResult = RefreshResult(1, 7, 0, 0, now, newEpisodes = 2) }
        val refresher = refresher(repo)
        refresher.messages.test {
            assertEquals(7, refresher.refreshProgram(ProgramId(1))?.episodes)
            assertEquals(sentences(UiText.Plural(R.plurals.sync_new_episodes, 2)), awaitItem())
        }
        assertEquals(1, repo.programCalls)
        assertEquals(0, repo.calls)
    }

    /** 番組単位の取り込みでも、番組がサーバ上で見つからなければ「最新の状態です」ではなくそう出す。 */
    @Test
    fun refreshProgramOfAGoneProgramSaysSo() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { programResult = RefreshResult(0, 0, 0, 0, now, onHold = 1) }
        val refresher = refresher(repo)
        refresher.messages.test {
            refresher.refreshProgram(ProgramId(1))
            assertEquals(UiText.Res(R.string.sync_program_gone), awaitItem())
        }
    }

    @Test
    fun refreshProgramFallsBackToAFullSyncWithoutServerId() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { programResult = null; result = RefreshResult(2, 9, 0, 0, now) }
        val refresher = refresher(repo)
        refresher.messages.test {
            assertEquals(9, refresher.refreshProgram(ProgramId(1))?.episodes)
            assertEquals(upToDate, awaitItem())
        }
        assertEquals(1, repo.calls)
    }

    @Test
    fun reconcileMessageComesBeforeTheResult() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { result = RefreshResult(1, 2, 0, 0, now) }
        val refresher = refresher(repo, downloads = FakeDownloads(missing = 2))
        refresher.messages.test {
            refresher.refresh()
            assertEquals(UiText.Plural(R.plurals.sync_reconciled, 2), awaitItem())
            assertEquals(upToDate, awaitItem())
        }
    }

    @Test
    fun unauthorizedSignsOut() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { error = ServerException.Unauthorized() }
        val session = FakeSessionRepository()
        val refresher = refresher(repo, session = session)

        refresher.messages.test {
            assertNull(refresher.refresh())
            assertEquals(UiText.Res(R.string.sync_error_session_expired), awaitItem())
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
            assertEquals(UiText.Res(R.string.sync_error_unreachable), awaitItem())
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

    @Test
    fun silentRefreshDoesNotShowTheSpinner() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRefreshRepository().apply { result = RefreshResult(1, 1, 0, 0, now); this.gate = gate }
        val refresher = refresher(repo)

        assertFalse(refresher.isSyncingInBackground.value)
        val silent = async { refresher.refresh(silent = true) }
        runCurrent()
        assertFalse(refresher.isRefreshing.value)
        assertTrue(refresher.isSyncingInBackground.value, "shows the background bar instead")
        assertNull(refresher.refresh(silent = true), "another silent call returns immediately")
        assertTrue(refresher.isSyncingInBackground.value)
        gate.complete(Unit)
        assertEquals(1, silent.await()?.programs)
        assertFalse(refresher.isRefreshing.value)
        assertFalse(refresher.isSyncingInBackground.value)
    }

    /** 手動だけの実行では裏の同期の表示を出さない（#138）。 */
    @Test
    fun manualRefreshDoesNotShowTheBackgroundBar() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRefreshRepository().apply { result = RefreshResult(1, 1, 0, 0, now); this.gate = gate }
        val refresher = refresher(repo)

        refresher.isSyncingInBackground.test {
            assertFalse(awaitItem())
            val manual = async { refresher.refresh() }
            runCurrent()
            assertTrue(refresher.isRefreshing.value)
            gate.complete(Unit)
            assertEquals(1, manual.await()?.programs)
            expectNoEvents()
        }
    }

    /** 失敗しても裏の同期の表示は消える（文言は出さない）。 */
    @Test
    fun silentRefreshErrorClearsTheBackgroundBar() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRefreshRepository().apply { error = ServerException.Unreachable(); this.gate = gate }
        val refresher = refresher(repo)

        val silent = async { refresher.refresh(silent = true) }
        runCurrent()
        assertTrue(refresher.isSyncingInBackground.value)
        gate.complete(Unit)
        assertNull(silent.await())
        assertFalse(refresher.isSyncingInBackground.value)
        assertFalse(refresher.isRefreshing.value)
    }

    @Test
    fun manualRefreshJoinsASilentOne() = joinsASilentRefresh { refresh() }

    @Test
    fun refreshProgramJoinsASilentRefresh() = joinsASilentRefresh { refreshProgram(ProgramId(1)) }

    @Test
    fun syncProgramJoinsASilentRefresh() = joinsASilentRefresh { syncProgram(ProgramId(1)) }

    /**
     * silent な全走査の最中に [manual] を呼ぶと、裏の同期の表示が消えてクルクルに切り替わり（#138）、
     * 走っている全走査の結果が 1 回だけ出て返る。変化が無くても手動なので「最新の状態です」が出る（#142）。
     */
    private fun joinsASilentRefresh(manual: suspend LibraryRefresher.() -> RefreshResult?) = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRefreshRepository().apply {
            result = RefreshResult(3, 40, 0, 0, now)
            programResult = RefreshResult(1, 7, 0, 0, now)
            syncProgramResult = RefreshResult(1, 6, 0, 0, now)
            this.gate = gate
        }
        val refresher = refresher(repo)

        refresher.messages.test {
            val silent = async { refresher.refresh(silent = true) }
            runCurrent()
            assertFalse(refresher.isRefreshing.value)
            assertTrue(refresher.isSyncingInBackground.value)

            val joined = async { refresher.manual() }
            runCurrent()
            assertTrue(refresher.isRefreshing.value)
            assertFalse(refresher.isSyncingInBackground.value, "the spinner replaces the background bar")
            assertFalse(joined.isCompleted, "waits for the running sync")

            gate.complete(Unit)
            assertEquals(40, joined.await()?.episodes)
            assertEquals(40, silent.await()?.episodes)
            assertEquals(upToDate, awaitItem())
            expectNoEvents()
        }
        assertFalse(refresher.isRefreshing.value)
        assertFalse(refresher.isSyncingInBackground.value)
        assertEquals(1, repo.calls)
        assertEquals(0, repo.programCalls)
        assertEquals(0, repo.syncProgramCalls)
    }

    /** silent なうちに変化を出した後（Worker を起こしている間）に合流しても、その文言は二度は出ない（#142）。 */
    @Test
    fun joiningAfterASilentReportDoesNotRepeatIt() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { result = RefreshResult(3, 40, 0, 0, now, enqueued = 2) }
        val kickGate = CompletableDeferred<Unit>()
        val kicker = FakeKicker().apply { gate = kickGate }
        val refresher = refresher(repo, kicker = kicker)

        refresher.messages.test {
            val silent = async { refresher.refresh(silent = true) }
            runCurrent()
            assertEquals(1, kicker.kicks)
            assertEquals(sentences(actions(UiText.Plural(R.plurals.sync_enqueued, 2))), awaitItem())

            val joined = async { refresher.refresh() }
            runCurrent()
            expectNoEvents()
            assertTrue(refresher.isRefreshing.value)

            kickGate.complete(Unit)
            assertEquals(40, joined.await()?.episodes)
            assertEquals(40, silent.await()?.episodes)
            expectNoEvents()
        }
        assertFalse(refresher.isRefreshing.value)
        assertEquals(1, repo.calls)
    }

    @Test
    fun joiningASilentRefreshReportsItsError() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRefreshRepository().apply { error = ServerException.Unreachable(); this.gate = gate }
        val refresher = refresher(repo)

        refresher.messages.test {
            val silent = async { refresher.refresh(silent = true) }
            runCurrent()
            val joined = async { refresher.refresh() }
            runCurrent()
            gate.complete(Unit)
            assertNull(joined.await())
            assertNull(silent.await())
            assertEquals(UiText.Res(R.string.sync_error_unreachable), awaitItem())
            expectNoEvents()
        }
        assertFalse(refresher.isRefreshing.value)
        assertEquals(1, repo.calls)
    }

    @Test
    fun silentRefreshErrorStaysQuietWithoutAJoin() = runTest(StandardTestDispatcher()) {
        val repo = FakeRefreshRepository().apply { error = ServerException.Unreachable() }
        val refresher = refresher(repo)
        refresher.messages.test {
            assertNull(refresher.refresh(silent = true))
            expectNoEvents()
        }
    }

    @Test
    fun silentCallDuringAManualRefreshIsIgnored() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRefreshRepository().apply { result = RefreshResult(1, 1, 0, 0, now); this.gate = gate }
        val refresher = refresher(repo)

        refresher.messages.test {
            val manual = async { refresher.refresh() }
            runCurrent()
            assertNull(refresher.refresh(silent = true))
            assertTrue(refresher.isRefreshing.value)
            gate.complete(Unit)
            assertEquals(1, manual.await()?.programs)
            assertEquals(upToDate, awaitItem())
            expectNoEvents()
        }
        assertEquals(1, repo.calls)
        assertFalse(refresher.isRefreshing.value)
    }
}

/** 「最新の状態です」だけの文言。 */
private val upToDate: UiText = sentences(UiText.Res(R.string.sync_up_to_date))

/** 文を「。」でつないだ形（[LibraryRefresher] が作る [UiText.Joined] と同じ構造）。 */
private fun sentences(vararg parts: UiText): UiText = UiText.Joined(parts.toList(), UiText.Res(R.string.sync_sentence_separator))

/** 「N 回をダウンロード予約、M 回を削除」の部分。 */
private fun actions(vararg parts: UiText): UiText = UiText.Joined(parts.toList(), UiText.Res(R.string.common_list_separator))
