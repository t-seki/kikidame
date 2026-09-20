package dev.tseki.kikidame.playback
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.PlaybackState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ResumeOnTransitionTest {
    private val now = Instant.parse("2026-09-16T00:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = this@ResumeOnTransitionTest.now
    }
    private val repo = InMemoryPlaybackStateRepository(clock)
    private val player = FakePlayer()
    private val runtime = 30.minutes
    private fun item(id: Long) = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri("file:///tmp/$id.m4a")
        .setMediaMetadata(MediaMetadata.Builder().setDurationMs(runtime.inWholeMilliseconds).build())
        .build()
    @Test
    fun seeksToSavedPositionOnNext() = runTest(StandardTestDispatcher()) {
        repo.states.value = mapOf(EpisodeId(2) to PlaybackState(EpisodeId(2), 7.minutes, played = false, updatedAt = now))
        player.setPlaylist(listOf(item(1), item(2)), listOf(runtime.inWholeMilliseconds, runtime.inWholeMilliseconds))
        ResumeOnTransition(player, repo, this).attach()
        player.seekToNextMediaItem()
        player.update { }
        runCurrent()
        player.update { }
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(7.minutes.inWholeMilliseconds, player.currentPosition)
    }
    @Test
    fun startsFromBeginningWhenSavedPositionIsNearEnd() = runTest(StandardTestDispatcher()) {
        repo.states.value = mapOf(EpisodeId(2) to PlaybackState(EpisodeId(2), 29.minutes, played = true, updatedAt = now))
        player.setPlaylist(listOf(item(1), item(2)), listOf(runtime.inWholeMilliseconds, runtime.inWholeMilliseconds))
        ResumeOnTransition(player, repo, this).attach()
        player.seekToNextMediaItem()
        player.update { }
        runCurrent()
        player.update { }
        assertEquals(0L, player.currentPosition)
    }
    @Test
    fun ignoresPlaylistChange() = runTest(StandardTestDispatcher()) {
        repo.states.value = mapOf(EpisodeId(1) to PlaybackState(EpisodeId(1), 7.minutes, played = false, updatedAt = now))
        ResumeOnTransition(player, repo, this).attach()
        player.setPlaylist(listOf(item(1)), listOf(runtime.inWholeMilliseconds))
        runCurrent()
        player.update { }
        assertEquals(0L, player.currentPosition, "the caller passes the start position with setMediaItems")
    }
}
