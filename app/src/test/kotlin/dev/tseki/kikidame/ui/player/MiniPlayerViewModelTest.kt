package dev.tseki.kikidame.ui.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.Episode
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LocalFile
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ProgramImage
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.playback.NowPlayingState
import dev.tseki.kikidame.playback.PlayerConnection
import dev.tseki.kikidame.testing.InMemoryLibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** ミニプレイヤーに出す、聴いている回の番組の画像（#289）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MiniPlayerViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val library = InMemoryLibraryRepository()
    private val nowPlaying = NowPlaying()
    private val withImage = Program(id = ProgramId(1), sourceItemId = null, name = "画像あり", publisherName = "TBSラジオ", image = ProgramImage("/files/1.jpg"))
    private val without = Program(id = ProgramId(2), sourceItemId = null, name = "画像なし", publisherName = "TBSラジオ")

    @Before
    fun setMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun episode(id: Long, program: Program) = EpisodeWithState(
        episode = Episode(
            id = EpisodeId(id), sourceItemId = null, programId = program.id, title = "回 $id",
            publishedAt = Instant.parse("2026-09-01T15:00:00Z"), addedAt = null, runtime = 60.minutes, sizeBytes = 1, container = "m4a",
        ),
        localFile = LocalFile(EpisodeId(id), DownloadState.DONE, "/tmp/$id.m4a", pinned = false),
        playback = null,
    )

    private fun playing(id: Long, positionMs: Long = 0) =
        NowPlayingState(EpisodeId(id), "回 $id", "番組", isPlaying = true, positionMs = positionMs, durationMs = 3_600_000)

    @Test
    fun programImageFollowsTheEpisodeBeingPlayedAndIsNullWithoutOne() = runTest(dispatcher) {
        library.programs.value = listOf(withImage, without)
        library.episodes.value = listOf(episode(1, withImage), episode(2, without))
        val vm = MiniPlayerViewModel(nowPlaying, library, PlayerConnection(context))
        backgroundScope.launch(dispatcher) { vm.programImage.collect {} }

        assertNull(vm.programImage.value)
        nowPlaying.set(playing(1))
        assertEquals(ProgramImage("/files/1.jpg"), vm.programImage.value)
        // 再生位置だけが変わっても画像は変わらない
        nowPlaying.set(playing(1, positionMs = 5_000))
        assertEquals(ProgramImage("/files/1.jpg"), vm.programImage.value)
        nowPlaying.set(playing(2))
        assertNull(vm.programImage.value)
        nowPlaying.set(null)
        assertNull(vm.programImage.value)
    }

    @Test
    fun programImageFollowsTheProgramWhenSyncReplacesIt() = runTest(dispatcher) {
        library.programs.value = listOf(without)
        library.episodes.value = listOf(episode(2, without))
        val vm = MiniPlayerViewModel(nowPlaying, library, PlayerConnection(context))
        backgroundScope.launch(dispatcher) { vm.programImage.collect {} }
        nowPlaying.set(playing(2))
        assertNull(vm.programImage.value)

        library.programs.value = listOf(without.copy(image = ProgramImage("/files/2.jpg")))
        assertEquals(ProgramImage("/files/2.jpg"), vm.programImage.value)
    }
}
