package dev.tseki.kikidame.ui.episodes

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.RetentionRule
import dev.tseki.kikidame.download.DownloadScheduler
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.text.UiText
import dev.tseki.kikidame.testing.FakeAppSettingsRepository
import dev.tseki.kikidame.testing.FakeDownloadRepository
import dev.tseki.kikidame.testing.FakeSessionRepository
import dev.tseki.kikidame.testing.IdleRefreshRepository
import dev.tseki.kikidame.testing.InMemoryLibraryRepository
import dev.tseki.kikidame.testing.InMemoryLibraryRepository.SyncUpdate
import dev.tseki.kikidame.testing.InMemoryPlaybackStateRepository
import dev.tseki.kikidame.testing.idleLibraryRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

/**
 * 各回一覧の ViewModel の同期対象の切り替え（[EpisodeListViewModel.setSyncEnabled] と OFF の確認 [EpisodeListViewModel.pendingDisable]）と、
 * 同期のシートを閉じたときの 1 番組の同期（[EpisodeListViewModel.onSyncSheetClosed]。#311）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EpisodeListViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val library = InMemoryLibraryRepository()
    private val downloads = FakeDownloadRepository()
    private val settings = FakeAppSettingsRepository()
    private val session = FakeSessionRepository()
    private val refreshRepository = IdleRefreshRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // DownloadScheduler は作った時点で WorkManager を引く
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun program(id: Long, syncEnabled: Boolean = false, rule: RetentionRule = RetentionRule()) =
        Program(id = ProgramId(id), sourceItemId = null, name = "番組 $id", publisherName = "TBSラジオ", syncEnabled = syncEnabled, retentionRule = rule)

    private fun TestScope.viewModel(programId: Long) = EpisodeListViewModel(
        savedStateHandle = SavedStateHandle(mapOf("programId" to programId)),
        library = library,
        playbackStates = InMemoryPlaybackStateRepository(Clock.System),
        clock = Clock.System,
        sessionRepository = session,
        refresher = idleLibraryRefresher(backgroundScope, session, downloads, settings, repository = refreshRepository),
        downloads = downloads,
        scheduler = DownloadScheduler(context, downloads, settings, session),
        nowPlaying = NowPlaying(),
    )

    /** ON にするとき、上限が未設定なら既定の 3 を入れる。上限と「再生済みは消す」が設定済みならそのまま。 */
    @Test
    fun enablingFillsTheDefaultLimitOnlyWhenItIsUnset() = runTest(dispatcher) {
        val keeping = RetentionRule(keepLatest = 5, deleteAfterPlayed = true)
        library.programs.value = listOf(program(1), program(2, rule = keeping))

        viewModel(1).setSyncEnabled(true)
        viewModel(2).setSyncEnabled(true)

        assertEquals(
            listOf(
                SyncUpdate(ProgramId(1), true, RetentionRule(keepLatest = EpisodeListViewModel.DEFAULT_KEEP_LATEST)),
                SyncUpdate(ProgramId(2), true, keeping),
            ),
            library.syncUpdates,
        )
    }

    /** 手元に消えるファイルが無ければ、確認せずに OFF にする（保持ルールはそのまま）。 */
    @Test
    fun disablingWithoutLocalFilesSavesAtOnce() = runTest(dispatcher) {
        val rule = RetentionRule(keepLatest = 3)
        library.programs.value = listOf(program(1, syncEnabled = true, rule = rule))
        library.unpinnedLocalFiles = 0
        val vm = viewModel(1)

        vm.setSyncEnabled(false)

        assertNull(vm.pendingDisable.value)
        assertEquals(listOf(SyncUpdate(ProgramId(1), false, rule)), library.syncUpdates)
    }

    /** シートを閉じたときの同期で消える回があれば、その数を出して確認を待つ。やめれば何も変えず、確かめれば OFF にする。 */
    @Test
    fun disablingWithLocalFilesWaitsForConfirmation() = runTest(dispatcher) {
        val rule = RetentionRule(keepLatest = 3)
        library.programs.value = listOf(program(1, syncEnabled = true, rule = rule))
        library.unpinnedLocalFiles = 2
        val vm = viewModel(1)

        vm.setSyncEnabled(false)
        assertEquals(2, vm.pendingDisable.value)
        assertEquals(emptyList(), library.syncUpdates)

        vm.cancelDisableSync()
        assertNull(vm.pendingDisable.value)
        assertEquals(emptyList(), library.syncUpdates)

        vm.setSyncEnabled(false)
        assertEquals(2, vm.pendingDisable.value)
        vm.confirmDisableSync()
        assertNull(vm.pendingDisable.value)
        assertEquals(listOf(SyncUpdate(ProgramId(1), false, rule)), library.syncUpdates)
        assertEquals(false, library.programs.value.single().syncEnabled)
    }

    /** 「固定する」は手元の回を固定にするよう頼み、文言で知らせる（番組が同期対象かは見ない）。 */
    @Test
    fun pinAsksTheRepositoryAndSaysSo() = runTest(dispatcher) {
        library.programs.value = listOf(program(1, syncEnabled = false))
        val vm = viewModel(1)
        val messages = mutableListOf<UiText>()
        backgroundScope.launch { vm.messages.toList(messages) }

        vm.pin(EpisodeId(7))

        assertEquals(listOf(EpisodeId(7)), downloads.pinned)
        assertEquals(listOf<UiText>(UiText.Res(R.string.episode_list_msg_pinned)), messages)
    }

    /** シートを開いている間（[viewModel] の program を画面のように購読している間）の ViewModel。 */
    private fun TestScope.openSheet(programId: Long): EpisodeListViewModel = viewModel(programId).also { vm ->
        backgroundScope.launch { vm.program.collect {} }
        vm.onSyncSheetOpened()
    }

    /** 設定を変えている間は同期せず、閉じたときに開いたときから変わっていれば、その番組だけを同期する（#311 の決定 1）。 */
    @Test
    fun closingTheSheetSyncsTheProgramWhenTheSettingsChanged() = runTest(dispatcher) {
        library.programs.value = listOf(program(1), program(2))
        val vm = openSheet(1)

        vm.setSyncEnabled(true)
        vm.setKeepLatest(5)
        vm.setDeleteAfterPlayed(true)
        assertEquals(emptyList(), refreshRepository.syncedPrograms, "no sync while the sheet is open")

        vm.onSyncSheetClosed()
        assertEquals(listOf(ProgramId(1)), refreshRepository.syncedPrograms)
    }

    /** 保持ルールだけを変えて閉じても同期する（件数を減らして閉じれば、その場で消える。#311 の決定 2）。 */
    @Test
    fun closingTheSheetSyncsWhenOnlyTheRuleChanged() = runTest(dispatcher) {
        library.programs.value = listOf(program(1, syncEnabled = true, rule = RetentionRule(keepLatest = 5)))
        val vm = openSheet(1)

        vm.setKeepLatest(1)
        vm.onSyncSheetClosed()

        assertEquals(listOf(ProgramId(1)), refreshRepository.syncedPrograms)
    }

    /** 何も変えずに閉じたら同期しない。 */
    @Test
    fun closingTheSheetWithoutChangesDoesNotSync() = runTest(dispatcher) {
        library.programs.value = listOf(program(1, syncEnabled = true, rule = RetentionRule(keepLatest = 3)))
        val vm = openSheet(1)

        vm.onSyncSheetClosed()

        assertEquals(emptyList(), refreshRepository.syncedPrograms)
    }

    /**
     * ON にして OFF に戻して閉じたら同期しない。ON にしたときに上限の既定の 3 が入って保持ルールは変わるが、
     * OFF の番組の同期は保持ルールを見ないので、変わったとはしない（#311 の決定 1）。
     */
    @Test
    fun closingTheSheetAfterTurningSyncOnAndOffAgainDoesNotSync() = runTest(dispatcher) {
        library.programs.value = listOf(program(1))
        library.unpinnedLocalFiles = 0
        val vm = openSheet(1)

        vm.setSyncEnabled(true)
        vm.setSyncEnabled(false)
        assertEquals(RetentionRule(keepLatest = EpisodeListViewModel.DEFAULT_KEEP_LATEST), library.programs.value.single().retentionRule)
        vm.onSyncSheetClosed()

        assertEquals(emptyList(), refreshRepository.syncedPrograms)
    }

    /** 保持ルールを変えてから元の値に戻して閉じたら同期しない。 */
    @Test
    fun closingTheSheetAfterRestoringTheRuleDoesNotSync() = runTest(dispatcher) {
        library.programs.value = listOf(program(1, syncEnabled = true, rule = RetentionRule(keepLatest = 3)))
        val vm = openSheet(1)

        vm.setKeepLatest(10)
        vm.setDeleteAfterPlayed(true)
        vm.setKeepLatest(3)
        vm.setDeleteAfterPlayed(false)
        vm.onSyncSheetClosed()

        assertEquals(emptyList(), refreshRepository.syncedPrograms)
    }

    /** 閉じたときの同期は 1 回だけ。閉じた後にもう一度閉じたと伝わっても（開かずに）同期しない。 */
    @Test
    fun closingTwiceSyncsOnce() = runTest(dispatcher) {
        library.programs.value = listOf(program(1))
        val vm = openSheet(1)

        vm.setSyncEnabled(true)
        vm.onSyncSheetClosed()
        vm.onSyncSheetClosed()

        assertEquals(listOf(ProgramId(1)), refreshRepository.syncedPrograms)
    }
}
