package dev.tseki.kikidame.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * タイマーの発火・一時停止・解除の規則。コルーチンの仮想時間で delay を進め、時計もそれに合わせて進める。
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
        setPlayWhenReady(true)
    }

    private fun setPlayWhenReady(value: Boolean) {
        player.playWhenReady = value
        player.update { }
    }

    /** 仮想時間と時計を一緒に進める。 */
    private fun TestScope.pass(duration: Duration) {
        now += duration
        advanceTimeBy(duration)
        player.update { }
        runCurrent()
    }

    private fun remaining(): Duration? = (timer.setting.value as? SleepTimerSetting.Countdown)?.remainingAt(now)

    private fun withRunner(block: suspend TestScope.(SleepTimerRunner) -> Unit) =
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
        timer.set(SleepTimerSetting.Countdown(15.minutes))
        runCurrent()
        pass(14.minutes)
        assertTrue(player.playWhenReady, "まだ止めない")
        assertEquals(1.minutes, remaining())
        pass(2.minutes)
        assertFalse(player.playWhenReady, "15 分で一時停止")
        assertNull(timer.setting.value, "発火したら解除")
    }

    /** トイレに立って一時停止しても消えない。止めている間は減らず、再開したら続きから。 */
    @Test
    fun pausingFreezesTheCountdownAndResumingContinuesIt() = withRunner {
        startPlaying()
        timer.set(SleepTimerSetting.Countdown(30.minutes))
        runCurrent()
        pass(10.minutes)
        setPlayWhenReady(false)
        runCurrent()
        assertEquals(20.minutes, remaining(), "一時停止した時点の残り")
        pass(25.minutes)
        assertEquals(20.minutes, remaining(), "止めている間は減らない")
        setPlayWhenReady(true)
        runCurrent()
        pass(19.minutes)
        assertTrue(player.playWhenReady, "再開後 19 分ではまだ止めない")
        pass(2.minutes)
        assertFalse(player.playWhenReady, "残り 20 分を使い切って一時停止")
        assertNull(timer.setting.value)
    }

    /** 止まっているときに選んだら、再生を始めた時点から数える。 */
    @Test
    fun choosingWhilePausedStartsCountingOnPlay() = withRunner {
        player.setPlaylist(listOf(item(1)), listOf(3_600_000L))
        player.update { setPlaybackState(Player.STATE_READY) }
        timer.set(SleepTimerSetting.Countdown(15.minutes))
        runCurrent()
        pass(30.minutes)
        assertEquals(15.minutes, remaining(), "再生していないので減らない")
        setPlayWhenReady(true)
        runCurrent()
        pass(16.minutes)
        assertFalse(player.playWhenReady)
        assertNull(timer.setting.value)
    }

    @Test
    fun endOfEpisodeUsesPauseAtEndOfMediaItemsAndSurvivesPause() = withRunner {
        startPlaying()
        timer.set(SleepTimerSetting.EndOfEpisode)
        runCurrent()
        assertTrue(pauseAtEnd)
        setPlayWhenReady(false)
        runCurrent()
        assertEquals(SleepTimerSetting.EndOfEpisode, timer.setting.value, "一時停止では消えない")
        assertTrue(pauseAtEnd)
        timer.set(null)
        runCurrent()
        assertFalse(pauseAtEnd)
    }

    /** 途中の回で「この回の終わりまで」が発火したら解除。次の回まで持ち越さない（キューに次があるので STATE_ENDED にはならない）。 */
    @Test
    fun endOfEpisodeFiringClearsTheTimer() = withRunner {
        startPlaying()
        timer.set(SleepTimerSetting.EndOfEpisode)
        runCurrent()
        player.update { setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) }
        runCurrent()
        assertNull(timer.setting.value, "回の終わりで止めた = 発火")
        assertFalse(pauseAtEnd)
    }

    @Test
    fun choosingAgainReplacesTheCountdown() = withRunner {
        startPlaying()
        timer.set(SleepTimerSetting.Countdown(15.minutes))
        runCurrent()
        timer.set(SleepTimerSetting.Countdown(45.minutes))
        runCurrent()
        pass(16.minutes)
        assertTrue(player.playWhenReady, "最初の 15 分では止まらない")
        pass(30.minutes)
        assertFalse(player.playWhenReady, "選び直した 45 分で止まる")
    }

    @Test
    fun endedClearsTheTimer() = withRunner {
        startPlaying()
        timer.set(SleepTimerSetting.Countdown(30.minutes))
        runCurrent()
        player.update { setPlaybackState(Player.STATE_ENDED) }
        runCurrent()
        assertNull(timer.setting.value, "回が終わり切ったら待つものが無い")
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
