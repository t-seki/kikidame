package dev.tseki.kikidame.ui.programs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.Episode
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LocalDeletionScope
import dev.tseki.kikidame.domain.LocalFile
import dev.tseki.kikidame.domain.PlaybackState
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.playback.InMemoryLibraryRepository
import dev.tseki.kikidame.playback.InMemoryPlaybackStateRepository
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.playback.NowPlayingState
import dev.tseki.kikidame.ui.UiText
import dev.tseki.kikidame.ui.resolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** 「続きから」タブ（#151）の ViewModel と行の補足。補足は並び・省略を見るので日本語で解決して比べる。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "ja")
class ContinueListeningViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-10-07T00:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = this@ContinueListeningViewModelTest.now
    }
    private val dispatcher = UnconfinedTestDispatcher()
    private val library = InMemoryLibraryRepository()
    private val playbackStates = InMemoryPlaybackStateRepository(clock)
    private val downloads = FakeDownloads()
    private val nowPlaying = NowPlaying()
    private val harai = Program(id = ProgramId(1), serverItemId = null, name = "ハライチのターン！", publisherName = "TBSラジオ", syncEnabled = true)
    private val ann = Program(id = ProgramId(2), serverItemId = null, name = "ANN", publisherName = "ニッポン放送")

    @Before
    fun setMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun viewModel() = ContinueListeningViewModel(library, playbackStates, clock, downloads, nowPlaying)

    private fun episode(
        id: Long,
        program: Program,
        position: Duration?,
        updatedAt: Instant = now,
        played: Boolean = false,
        runtime: Duration = 60.minutes,
    ) = EpisodeWithState(
        episode = Episode(
            id = EpisodeId(id), serverItemId = null, programId = program.id, title = "回 $id",
            publishedAt = Instant.parse("2026-09-01T15:00:00Z"), addedAt = null, runtime = runtime, sizeBytes = 1, container = "m4a",
        ),
        localFile = LocalFile(EpisodeId(id), DownloadState.DONE, "/tmp/$id.m4a", pinned = false),
        playback = position?.let { PlaybackState(EpisodeId(id), it, played = played, updatedAt = updatedAt) },
    )

    /** `WhileSubscribed` なので、値を見る間は購読しておく。 */
    private fun TestScope.subscribe(vm: ContinueListeningViewModel) {
        backgroundScope.launch(dispatcher) { vm.items.collect {} }
        backgroundScope.launch(dispatcher) { vm.nowPlaying.collect {} }
    }

    @Test
    fun itemsAreInProgressEpisodesAcrossProgramsNewestRecordFirstAndFollowChanges() = runTest(dispatcher) {
        library.programs.value = listOf(harai, ann)
        library.episodes.value = listOf(
            episode(1, harai, 10.minutes, updatedAt = now - 2.minutes),
            episode(2, ann, 20.minutes, updatedAt = now - 1.minutes),
            episode(3, harai, 2.seconds), // 聴き始めていない
            episode(4, ann, 30.minutes, played = true), // 再生済み
            episode(5, ann, null), // 記録なし
        )
        val vm = viewModel()
        subscribe(vm)
        assertEquals(listOf(2L to "ANN", 1L to "ハライチのターン！"), vm.items.value!!.map { it.episode.episode.id.value to it.program?.name })

        // 再生が進むと先頭へ、再生済みになると消える（Room の Flow の代わりに手元の一覧を書き換える）
        library.episodes.value = listOf(
            episode(1, harai, 11.minutes, updatedAt = now),
            episode(2, ann, 20.minutes, updatedAt = now - 1.minutes, played = true),
        )
        assertEquals(listOf(1L), vm.items.value!!.map { it.episode.episode.id.value })

        // 同期で消えると空になる
        library.episodes.value = emptyList()
        assertEquals(emptyList(), vm.items.value)
    }

    @Test
    fun nowPlayingDropsThePosition() = runTest(dispatcher) {
        val vm = viewModel()
        subscribe(vm)
        nowPlaying.set(NowPlayingState(EpisodeId(1), "回 1", "ハライチのターン！", isPlaying = true, positionMs = 1_000, durationMs = 60_000))
        assertEquals(NowPlayingState(EpisodeId(1), "回 1", "ハライチのターン！", isPlaying = true), vm.nowPlaying.value)
    }

    /** 再生済みにすると［元に戻す］の合図が出て、元に戻すと再生済みが外れる（位置はそのまま）。元に戻す側は合図を出さない。 */
    @Test
    fun markingPlayedSignalsUndoAndUndoUnmarks() = runTest(dispatcher) {
        val id = EpisodeId(1)
        playbackStates.states.value = mapOf(id to PlaybackState(id, 10.minutes, played = false, updatedAt = now - 1.minutes))
        val vm = viewModel()
        val signals = mutableListOf<EpisodeId>()
        backgroundScope.launch(dispatcher) { vm.markedPlayed.collect { signals += it } }

        vm.setPlayed(id, true)
        assertEquals(PlaybackState(id, 10.minutes, played = true, updatedAt = now), playbackStates.states.value[id])
        assertEquals(listOf(id), signals)

        vm.setPlayed(id, false)
        assertEquals(PlaybackState(id, 10.minutes, played = false, updatedAt = now), playbackStates.states.value[id])
        assertEquals(listOf(id), signals)
    }

    /** ファイルの削除は各回一覧と同じ文言（同期対象の番組なら「次の同期で落とし直されます」）。 */
    @Test
    fun deletingAFileSaysWhetherTheNextSyncBringsItBack() = runTest(dispatcher) {
        library.programs.value = listOf(harai, ann)
        library.episodes.value = listOf(episode(1, harai, 10.minutes), episode(2, ann, 10.minutes))
        val vm = viewModel()
        subscribe(vm)
        val messages = mutableListOf<UiText>()
        backgroundScope.launch(dispatcher) { vm.messages.collect { messages += it } }

        vm.deleteLocal(EpisodeId(1))
        vm.deleteLocal(EpisodeId(2))
        assertEquals(listOf(EpisodeId(1), EpisodeId(2)), downloads.deleted)
        assertEquals<List<UiText>>(
            listOf(UiText.Res(R.string.episode_list_msg_file_deleted_synced), UiText.Res(R.string.episode_list_msg_file_deleted)),
            messages,
        )
    }

    /** 行の補足は「番組名 · 残り N 分」。残りは分に切り上げ、尺が分からない回は番組名だけ。 */
    @Test
    fun supportingTextIsProgramNameAndMinutesLeft() {
        fun text(position: Duration, runtime: Duration = 60.minutes, program: Program? = harai) =
            ContinueListeningViewModel.Item(episode(1, harai, position, runtime = runtime), program).toSupportingText().resolve(context)
        assertEquals("ハライチのターン！ · 残り 25 分", text(35.minutes))
        assertEquals("ハライチのターン！ · 残り 25 分", text(35.minutes + 30.seconds))
        assertEquals("ハライチのターン！", text(35.minutes, runtime = Duration.ZERO))
        // 尺より先の位置（尺の情報が古い）は末尾付近の扱いで先頭から再開するので、残りは尺いっぱい
        assertEquals("ハライチのターン！ · 残り 30 分", text(40.minutes, runtime = 30.minutes))
        assertEquals("残り 25 分", text(35.minutes, program = null))
    }

    private class FakeDownloads : DownloadRepository {
        val deleted = mutableListOf<EpisodeId>()
        override suspend fun enqueue(episodeId: EpisodeId) = error("not used")
        override suspend fun enqueueForSync(episodeIds: List<EpisodeId>): Int = error("not used")
        override suspend fun removeEpisode(episodeId: EpisodeId) = error("not used")
        override suspend fun removeProgram(programId: ProgramId) = error("not used")
        override suspend fun cancel(episodeId: EpisodeId) = error("not used")
        override suspend fun retry(episodeId: EpisodeId) = error("not used")
        override suspend fun unpin(episodeId: EpisodeId) = error("not used")
        override suspend fun deleteLocal(episodeId: EpisodeId): LocalDeletionScope {
            deleted += episodeId
            return LocalDeletionScope.FILE_ONLY
        }
        override suspend fun reconcileMissingFiles(): Int = error("not used")
        override suspend fun ensureFilePresent(episodeId: EpisodeId): Boolean = error("not used")
    }
}
