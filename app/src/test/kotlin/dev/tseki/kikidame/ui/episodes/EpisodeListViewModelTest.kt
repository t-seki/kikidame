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

/** 各回一覧の ViewModel の同期対象の切り替え（[EpisodeListViewModel.setSyncEnabled] と OFF の確認 [EpisodeListViewModel.pendingDisable]）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EpisodeListViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val library = InMemoryLibraryRepository()
    private val downloads = FakeDownloadRepository()
    private val settings = FakeAppSettingsRepository()
    private val session = FakeSessionRepository()

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
        refresher = idleLibraryRefresher(backgroundScope, session, downloads, settings),
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

    /** 次の同期で消える回があれば、その数を出して確認を待つ。やめれば何も変えず、確かめれば OFF にする。 */
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
}
