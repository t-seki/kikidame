package dev.tseki.jellyfinradio.playback
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.PlaybackState
import dev.tseki.jellyfinradio.domain.PlaybackStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PositionPersisterTest {
    private val now = Instant.parse("2026-09-16T00:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = this@PositionPersisterTest.now
    }
    private val repo = InMemoryPlaybackStateRepository(clock)
    private val player = FakePlayer()
    private val ep1 = EpisodeId(1)
    private val ep2 = EpisodeId(2)
    private val runtime = 30.minutes
    private fun item(id: EpisodeId) = MediaItem.Builder()
        .setMediaId(EpisodeMediaItems.mediaId(id))
        .setUri("file:///tmp/${id.value}.m4a")
        .setMediaMetadata(MediaMetadata.Builder().setDurationMs(runtime.inWholeMilliseconds).build())
        .build()
    private fun setup(scope: CoroutineScope): PositionPersister {
        player.setPlaylist(listOf(item(ep1), item(ep2)), listOf(runtime.inWholeMilliseconds, runtime.inWholeMilliseconds))
        player.update { setPlaybackState(Player.STATE_READY) }
        return PositionPersister(player, repo, clock, playerScope = scope, persistScope = scope).also { it.attach() }
    }
    @Test
    fun savesPositionWhenPaused() = runTest(StandardTestDispatcher()) {
        setup(this)
        player.update { setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST); setContentPositionMs(5_000) }
        runCurrent()
        player.update { setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST); setContentPositionMs(65_000) }
        runCurrent()
        val saved = repo.states.value[ep1]!!
        assertEquals(65.seconds, saved.position)
        assertFalse(saved.played)
        assertNull(saved.syncedAt)
    }
    @Test
    fun savesEveryTenSecondsWhilePlaying() = runTest(StandardTestDispatcher()) {
        setup(this)
        player.update { setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        runCurrent()
        assertEquals(0, repo.updateCount)
        player.update { setContentPositionMs(10_000) }
        advanceTimeBy(10.seconds + 1.milliseconds)
        assertEquals(1, repo.updateCount)
        assertEquals(10.seconds, repo.states.value[ep1]!!.position)
        player.update { setContentPositionMs(20_000) }
        advanceTimeBy(10.seconds)
        assertEquals(2, repo.updateCount)
        assertEquals(20.seconds, repo.states.value[ep1]!!.position)
        player.update { setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        runCurrent()
        val afterPause = repo.updateCount
        advanceTimeBy(60.seconds)
        assertEquals(afterPause, repo.updateCount, "ticker must stop when paused")
    }
    /** 開いてすぐ ⏭ した回（位置 0〜数秒・未再生）には行を作らない（#7）。 */
    @Test
    fun skippingPastAnUnstartedEpisodeLeavesNoRow() = runTest(StandardTestDispatcher()) {
        setup(this)
        player.update { setContentPositionMs(2_000) }
        player.seekToNextMediaItem()
        player.update { }
        runCurrent()
        assertNull(repo.states.value[ep1])
        assertEquals(1, repo.updateCount, "update 自体は呼ばれるが書かれない")
        // ⏮ で戻っても同じ（方向は見ない）
        player.update { setContentPositionMs(1_000) }
        player.seekToPreviousMediaItem()
        player.update { }
        runCurrent()
        assertNull(repo.states.value[ep2])
        assertEquals(2, repo.updateCount)
    }

    @Test
    fun savesPreviousEpisodeWhenSkippingToNext() = runTest(StandardTestDispatcher()) {
        setup(this)
        player.update { setContentPositionMs(12 * 60_000L) }
        player.seekToNextMediaItem()
        player.update { }
        runCurrent()
        assertEquals(12.minutes, repo.states.value[ep1]!!.position)
        assertFalse(repo.states.value[ep1]!!.played)
        assertNull(repo.states.value[ep2])
    }
    @Test
    fun autoTransitionMarksPreviousEpisodeFullyPlayed() = runTest(StandardTestDispatcher()) {
        setup(this)
        player.update {
            setCurrentMediaItemIndex(1)
            setContentPositionMs(0)
            setPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, 0)
        }
        runCurrent()
        val saved = repo.states.value[ep1]!!
        assertEquals(runtime, saved.position)
        assertTrue(saved.played)
    }
    @Test
    fun endedStateMarksLastEpisodePlayed() = runTest(StandardTestDispatcher()) {
        setup(this)
        player.update { setCurrentMediaItemIndex(1); setPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK, 0) }
        runCurrent()
        player.update { setContentPositionMs(runtime.inWholeMilliseconds); setPlaybackState(Player.STATE_ENDED) }
        runCurrent()
        assertTrue(repo.states.value[ep2]!!.played)
        assertEquals(runtime, repo.states.value[ep2]!!.position)
    }
    @Test
    fun detachSavesCurrentPosition() = runTest(StandardTestDispatcher()) {
        val persister = setup(this)
        player.update { setContentPositionMs(42_000) }
        persister.detach()
        runCurrent()
        assertEquals(42.seconds, repo.states.value[ep1]!!.position)
    }
    @Test
    fun finalSaveSurvivesPlayerScopeCancellation() = runTest(StandardTestDispatcher()) {
        // サービス破棄: detach() の直後に player 側のスコープが cancel される
        val playerScope = CoroutineScope(SupervisorJob() + testScheduler.let { StandardTestDispatcher(it) })
        val slowRepo = object : PlaybackStateRepository by repo {
            override suspend fun update(episodeId: EpisodeId, transform: (PlaybackState) -> PlaybackState): PlaybackState {
                delay(1.milliseconds) // Room の withTransaction と同じく必ず中断する
                return repo.update(episodeId, transform)
            }
        }
        player.setPlaylist(listOf(item(ep1)), listOf(runtime.inWholeMilliseconds))
        player.update { setPlaybackState(Player.STATE_READY); setContentPositionMs(42_000) }
        val persister = PositionPersister(player, slowRepo, clock, playerScope, persistScope = this)
        persister.attach()
        persister.detach()
        playerScope.cancel()
        advanceTimeBy(1.seconds)
        assertEquals(42.seconds, repo.states.value[ep1]!!.position)
    }
}
