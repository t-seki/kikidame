package dev.tseki.kikidame.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.domain.EpisodeId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** 一時停止などの後に未送信の回を送る（再生の共有、ADR 0010 の決定 5）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackSharePusherTest {
    /** 未送信の行の代わり。[version] は手元の更新時刻の代わり。 */
    private data class Row(val episode: Long, val version: Int = 0)

    private val player = FakePlayer()
    private val pending = MutableStateFlow<List<Row>>(emptyList())
    private val sent = ArrayList<Pair<List<Long>, Set<EpisodeId>>>()
    private val runtime = 30.minutes

    private fun item(id: Long) = MediaItem.Builder()
        .setMediaId(EpisodeMediaItems.mediaId(EpisodeId(id)))
        .setUri("file:///tmp/$id.m4a")
        .setMediaMetadata(MediaMetadata.Builder().setDurationMs(runtime.inWholeMilliseconds).build())
        .build()

    private fun TestScope.setup(): PlaybackSharePusher<Row> {
        player.setPlaylist(listOf(item(1), item(2)), listOf(runtime.inWholeMilliseconds, runtime.inWholeMilliseconds))
        player.update { setPlaybackState(Player.STATE_READY) }
        return PlaybackSharePusher(
            player = player,
            scope = this,
            sendScope = this,
            pending = pending,
            key = { it },
            episodeOf = { EpisodeId(it.episode) },
            send = { rows, except -> sent += rows.map { it.episode } to except },
        ).also { it.attach() }
    }

    private fun play(playing: Boolean) = player.update { setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }

    @Test
    fun theEpisodeBeingPlayedIsSentWhenPaused() = runTest(StandardTestDispatcher()) {
        val pusher = setup()
        play(true)
        runCurrent()
        // 再生中の定期保存で未送信になっても、鳴っている間は送らない
        pending.value = listOf(Row(1))
        runCurrent()
        assertEquals(emptyList(), sent)

        play(false)
        runCurrent()

        assertEquals(listOf(listOf(1L) to emptySet<EpisodeId>()), sent)
        pusher.detach()
    }

    @Test
    fun aManualToggleOfAnotherEpisodeIsSentWhilePlaying() = runTest(StandardTestDispatcher()) {
        val pusher = setup()
        play(true)
        runCurrent()

        pending.value = listOf(Row(1), Row(2))
        runCurrent()

        assertEquals(listOf(listOf(2L) to setOf(EpisodeId(1))), sent)
        pusher.detach()
    }

    @Test
    fun theSameRowsAreNotSentTwiceButAChangedRowIs() = runTest(StandardTestDispatcher()) {
        val pusher = setup()
        pending.value = listOf(Row(2))
        runCurrent()
        // 送れなかった（印が残った）。同じ顔ぶれでは送り直さない
        pending.value = listOf(Row(2))
        runCurrent()
        assertEquals(1, sent.size)

        pending.value = listOf(Row(2, version = 1))
        runCurrent()
        assertEquals(2, sent.size)
        pusher.detach()
    }

    @Test
    fun movingToTheNextEpisodeSendsThePreviousOne() = runTest(StandardTestDispatcher()) {
        val pusher = setup()
        play(true)
        runCurrent()
        pending.value = listOf(Row(1))
        runCurrent()

        player.seekToNextMediaItem()
        player.update { }
        runCurrent()

        assertEquals(listOf(listOf(1L) to setOf(EpisodeId(2))), sent)
        pusher.detach()
    }
}
