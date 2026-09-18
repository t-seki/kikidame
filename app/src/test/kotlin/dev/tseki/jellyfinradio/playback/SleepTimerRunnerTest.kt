package dev.tseki.jellyfinradio.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * タイマーの発火・解除の規則。コルーチンの仮想時間で delay を進め、時計はそれに合わせて手で進める。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SleepTimerRunnerTest {
    private var now = Instant.parse("2026-09-19T23:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = this@SleepTimerRunnerTest.now
    }
    private val player = FakePlayer()
    private val timer = SleepTimer()
    private var pauseAtEnd = false

    private fun item(id: Long) = MediaItem.Builder().setMediaId(id.toString()).setUri("file:///tmp/$id.m4a").build()

    private fun startPlaying() {
        player.setPlaylist(listOf(item(1), item(2)), listOf(3_600_000L, 3_600_000L))
        player.update { setPlaybackState(Player.STATE_READY) }
        player.playWhenReady = true
        player.update { }
    }

    private fun withRunner(block: suspend kotlinx.coroutines.test.TestScope.(SleepTimerRunner) -> Unit) =
        runTest(StandardTestDispatcher()) {
            val runner = SleepTimerRunner(player, timer, clock, this) { pauseAtEnd = it }
            runner.attach()
            runCurrent()
            block(runner)
            runner.detach()
        }

    @Test
    fun pausesWhenTheTimeComesAndClearsItself() = withRunner {
        startPlaying()
        timer.set(SleepTimerSetting.At(now + 15.minutes))
        runCurrent()
        advanceTimeBy(14.minutes)
        assertTrue(player.playWhenReady, "まだ止めない")
        now += 15.minutes
        advanceTimeBy(2.minutes)
        player.update { }
        runCurrent()
        assertFalse(player.playWhenReady, "15 分で一時停止")
        assertNull(timer.setting.value, "発火したら解除")
    }

    @Test
    fun endOfEpisodeUsesPauseAtEndOfMediaItem() = withRunner {
        startPlaying()
        timer.set(SleepTimerSetting.EndOfEpisode)
        runCurrent()
        assertTrue(pauseAtEnd)
        timer.set(null)
        runCurrent()
        assertFalse(pauseAtEnd)
    }

    @Test
    fun pausingClearsTheTimerAndResumingDoesNotRevive() = withRunner {
        startPlaying()
        timer.set(SleepTimerSetting.At(now + 30.minutes))
        runCurrent()
        player.playWhenReady = false
        player.update { }
        runCurrent()
        assertNull(timer.setting.value, "一時停止で解除")
        player.playWhenReady = true
        player.update { }
        runCurrent()
        assertNull(timer.setting.value, "再開しても復活しない")
        now += 31.minutes
        advanceTimeBy(31.minutes)
        assertTrue(player.playWhenReady, "解除済みなので止まらない")
    }

    @Test
    fun choosingAgainReplacesTheCountdown() = withRunner {
        startPlaying()
        timer.set(SleepTimerSetting.At(now + 15.minutes))
        runCurrent()
        timer.set(SleepTimerSetting.At(now + 45.minutes))
        runCurrent()
        now += 16.minutes
        advanceTimeBy(16.minutes)
        player.update { }
        assertTrue(player.playWhenReady, "最初の 15 分では止まらない")
        now += 30.minutes
        advanceTimeBy(30.minutes)
        player.update { }
        runCurrent()
        assertFalse(player.playWhenReady, "選び直した 45 分で止まる")
    }

    @Test
    fun detachClearsEverything() = runTest(StandardTestDispatcher()) {
        val runner = SleepTimerRunner(player, timer, clock, this) { pauseAtEnd = it }
        runner.attach()
        startPlaying()
        timer.set(SleepTimerSetting.EndOfEpisode)
        runCurrent()
        assertTrue(pauseAtEnd)
        runner.detach()
        assertFalse(pauseAtEnd)
        assertNull(timer.setting.value)
    }
}
