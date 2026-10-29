package dev.tseki.kikidame.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.PlaybackState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** ▶ の直前の読み込み（再生の共有、ADR 0010 の決定 5、#355）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReadBeforePlayPlayerTest {
    private val now = Instant.parse("2026-10-27T00:00:00Z")
    private val inner = FakePlayer()
    private val ep1 = EpisodeId(1)
    private val runtime = 30.minutes
    private val reads = ArrayList<EpisodeId>()

    private fun item(id: EpisodeId) = MediaItem.Builder()
        .setMediaId(EpisodeMediaItems.mediaId(id))
        .setUri("file:///tmp/${id.value}.m4a")
        .setMediaMetadata(MediaMetadata.Builder().setDurationMs(runtime.inWholeMilliseconds).build())
        .build()

    private fun TestScope.setup(refresh: suspend (EpisodeId) -> PlaybackState?): ReadBeforePlayPlayer {
        inner.setPlaylist(listOf(item(ep1)), listOf(runtime.inWholeMilliseconds))
        inner.update { setPlaybackState(Player.STATE_READY); setContentPositionMs(3.minutes.inWholeMilliseconds) }
        return ReadBeforePlayPlayer(inner, this) { id -> reads += id; refresh(id) }
    }

    private fun TestScope.settle() {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun movesToTheServerPositionBeforePlaying() = runTest(StandardTestDispatcher()) {
        val player = setup { PlaybackState(ep1, 12.minutes, played = false, updatedAt = now) }

        player.play()
        settle()

        assertEquals(listOf(ep1), reads)
        assertTrue(inner.playWhenReady)
        assertEquals(12.minutes.inWholeMilliseconds, inner.currentPosition)
    }

    @Test
    fun playsFromTheLocalPositionWhenNothingIsTaken() = runTest(StandardTestDispatcher()) {
        val player = setup { null }

        player.play()
        settle()

        assertTrue(inner.playWhenReady)
        assertEquals(3.minutes.inWholeMilliseconds, inner.currentPosition)
    }

    @Test
    fun playsFromTheLocalPositionWhenTheReadFails() = runTest(StandardTestDispatcher()) {
        val player = setup { throw IllegalStateException("boom") }

        player.play()
        settle()

        assertTrue(inner.playWhenReady)
        assertEquals(3.minutes.inWholeMilliseconds, inner.currentPosition)
    }

    /** 取得元の値が末尾付近（ほかで聴き終えた）なら、保存位置の規則どおり先頭から。 */
    @Test
    fun aServerPositionNearTheEndStartsFromTheBeginning() = runTest(StandardTestDispatcher()) {
        val player = setup { PlaybackState(ep1, 29.minutes, played = true, updatedAt = now) }

        player.play()
        settle()

        assertTrue(inner.playWhenReady)
        assertEquals(0L, inner.currentPosition)
    }

    @Test
    fun pausingWhileReadingDoesNotPlay() = runTest(StandardTestDispatcher()) {
        val answer = CompletableDeferred<PlaybackState?>()
        val player = setup { answer.await() }

        player.play()
        settle()
        assertFalse(inner.playWhenReady, "not playing while reading")
        player.pause()
        settle()
        answer.complete(PlaybackState(ep1, 12.minutes, played = false, updatedAt = now))
        settle()

        assertFalse(inner.playWhenReady)
        assertEquals(3.minutes.inWholeMilliseconds, inner.currentPosition)
    }

    @Test
    fun pausingDoesNotRead() = runTest(StandardTestDispatcher()) {
        val player = setup { null }
        player.play()
        settle()
        reads.clear()

        player.pause()
        settle()

        assertFalse(inner.playWhenReady)
        assertEquals(emptyList(), reads)
    }

    /** 待っている間に出力先が外れたら、待っていた ▶ を取り消す（#358）。 */
    @Test
    fun cancelWhileReadingDoesNotPlayAndReturnsToPaused() = runTest(StandardTestDispatcher()) {
        val answer = CompletableDeferred<PlaybackState?>()
        val player = setup { answer.await() }

        player.play()
        settle()
        assertTrue(player.playWhenReady, "the pending play shows as playing")
        player.cancelPendingPlay()
        settle()
        answer.complete(PlaybackState(ep1, 12.minutes, played = false, updatedAt = now))
        settle()

        assertFalse(inner.playWhenReady)
        assertFalse(player.playWhenReady, "the session state is back to paused")
        assertEquals(3.minutes.inWholeMilliseconds, inner.currentPosition)
    }

    @Test
    fun playingAgainAfterACancelPlaysNormally() = runTest(StandardTestDispatcher()) {
        val answer = CompletableDeferred<PlaybackState?>()
        var first = true
        val player = setup { if (first) { first = false; answer.await() } else PlaybackState(ep1, 12.minutes, played = false, updatedAt = now) }

        player.play()
        settle()
        player.cancelPendingPlay()
        settle()
        player.play()
        settle()

        assertTrue(inner.playWhenReady)
        assertEquals(12.minutes.inWholeMilliseconds, inner.currentPosition)
    }

    @Test
    fun cancelWhenNotReadingChangesNothing() = runTest(StandardTestDispatcher()) {
        val player = setup { null }
        player.play()
        settle()

        player.cancelPendingPlay()
        settle()

        assertTrue(inner.playWhenReady)
        assertTrue(player.playWhenReady)
    }
}
