package dev.tseki.kikidame.ui.programs

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.testing.FakeAppSettingsRepository
import dev.tseki.kikidame.testing.FakeSessionRepository
import dev.tseki.kikidame.testing.InMemoryLibraryRepository
import dev.tseki.kikidame.testing.idleLibraryRefresher
import dev.tseki.kikidame.ui.programs.ProgramFilter.Publisher
import dev.tseki.kikidame.ui.programs.ProgramFilter.PublisherKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 番組一覧の ViewModel の [ProgramListViewModel.filtered]（検索語 #44・配信元の絞り込み #45・よく聴く #151）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProgramListViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val library = InMemoryLibraryRepository()
    private val tbs = PublisherKey("TBSラジオ")
    private val lfr = PublisherKey("ニッポン放送")
    private val qr = PublisherKey("文化放送")
    private val harai = program(1, "ハライチのターン！", tbs, starred = true)
    private val junk = program(2, "JUNK", tbs)
    private val ann = program(3, "オールナイトニッポン", lfr)
    private val agq = program(4, "超A&G", qr)

    @Before
    fun setMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun program(id: Long, name: String, publisher: PublisherKey, starred: Boolean = false) =
        Program(id = ProgramId(id), sourceItemId = null, name = name, publisherName = publisher.name, starred = starred)

    /** `WhileSubscribed` なので、値を見る間は購読しておく。 */
    private fun TestScope.viewModel(): ProgramListViewModel {
        val vm = ProgramListViewModel(library, FakeSessionRepository(), idleLibraryRefresher(backgroundScope), NowPlaying(), FakeAppSettingsRepository())
        backgroundScope.launch(dispatcher) { vm.filtered.collect {} }
        return vm
    }

    private fun ProgramListViewModel.names() = filtered.value!!.programs.map { it.program.name }

    @Test
    fun withoutFiltersEverythingIsShownWithThePublishersAndStarred() = runTest(dispatcher) {
        library.programs.value = listOf(harai, ann, junk)
        val filtered = viewModel().filtered.value!!

        assertEquals(listOf("ハライチのターン！", "オールナイトニッポン", "JUNK"), filtered.programs.map { it.program.name })
        assertEquals(listOf(Publisher(tbs, 2), Publisher(lfr, 1)), filtered.publishers)
        assertNull(filtered.publisher)
        assertFalse(filtered.isFiltering)
        assertEquals(listOf(harai), filtered.starred.map { it.program })
        assertTrue(filtered.hasStarred)
    }

    /** 検索語と配信元は重ねて効く。配信元の候補の番組数は検索語で絞らない。戻るボタン（clearFilters）で両方外れる。 */
    @Test
    fun queryAndPublisherNarrowTogetherAndClearFiltersResetsBoth() = runTest(dispatcher) {
        library.programs.value = listOf(harai, ann, junk)
        val vm = viewModel()

        vm.setQuery("ン")
        assertEquals(listOf("ハライチのターン！", "オールナイトニッポン"), vm.names())
        assertTrue(vm.filtered.value!!.isFiltering)
        assertEquals(listOf(Publisher(tbs, 2), Publisher(lfr, 1)), vm.filtered.value!!.publishers)

        vm.selectPublisher(tbs)
        assertEquals(listOf("ハライチのターン！"), vm.names())
        assertEquals(tbs, vm.filtered.value!!.publisher)

        vm.clearFilters()
        assertEquals(listOf("ハライチのターン！", "オールナイトニッポン", "JUNK"), vm.names())
        assertNull(vm.filtered.value!!.publisher)
        assertFalse(vm.filtered.value!!.isFiltering)
    }

    /** よく聴くは絞った後の一覧から選ぶ。絞った結果に無くても、よく聴く番組があること（hasStarred）は変わらない。 */
    @Test
    fun starredFollowsTheFilterButHasStarredDoesNot() = runTest(dispatcher) {
        library.programs.value = listOf(harai, ann, junk)
        val vm = viewModel()

        vm.selectPublisher(lfr)
        assertEquals(emptyList(), vm.filtered.value!!.starred)
        assertTrue(vm.filtered.value!!.hasStarred)

        library.programs.value = listOf(harai.copy(starred = false), ann, junk)
        assertFalse(vm.filtered.value!!.hasStarred)
    }

    /** 配信元が 2 種類未満なら絞り込み自体を出さないので、選んでいても選ばれていない扱いにし、選択も「すべて」に戻す。 */
    @Test
    fun aPublisherIsNotSelectedWhileThereAreFewerThanTwo() = runTest(dispatcher) {
        library.programs.value = listOf(harai, junk)
        val vm = viewModel()

        vm.selectPublisher(tbs)
        assertNull(vm.filtered.value!!.publisher)
        assertFalse(vm.filtered.value!!.isFiltering)
        assertEquals(listOf("ハライチのターン！", "JUNK"), vm.names())

        // 他の配信元が増えても、戻した選択は復活しない
        library.programs.value = listOf(harai, junk, ann)
        assertNull(vm.filtered.value!!.publisher)
        assertEquals(3, vm.filtered.value!!.programs.size)
    }

    /** 同期で選択中の配信元の番組が消えたら「すべて」に戻る。その配信元の番組が戻ってきても、選び直されない。 */
    @Test
    fun aSelectedPublisherThatDisappearsFallsBackToAll() = runTest(dispatcher) {
        library.programs.value = listOf(harai, ann, agq)
        val vm = viewModel()
        vm.selectPublisher(lfr)
        assertEquals(listOf("オールナイトニッポン"), vm.names())

        library.programs.value = listOf(harai, agq)
        assertNull(vm.filtered.value!!.publisher)
        assertEquals(listOf("ハライチのターン！", "超A&G"), vm.names())

        library.programs.value = listOf(harai, ann, agq)
        assertNull(vm.filtered.value!!.publisher)
        assertEquals(3, vm.filtered.value!!.programs.size)
    }
}
