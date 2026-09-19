package dev.tseki.jellyfinradio.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.jellyfinradio.domain.EpisodeId
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class NowPlayingTest {
    private val player = FakePlayer()
    private val nowPlaying = NowPlaying().also { player.addListener(it.listener(player, TestScope())) }

    private fun item(id: Long, title: String, program: String) = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri("file:///tmp/$id.m4a")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(program).build())
        .build()

    private fun load(vararg items: MediaItem) = player.setPlaylist(items.toList(), items.map { 1_800_000L })

    @Test
    fun nothingLoadedMeansNoNowPlaying() {
        assertNull(nowPlaying.state.value)
        assertNull(nowPlaying.excludedFromSync)
    }

    @Test
    fun loadingAPlaylistPicksUpTheCurrentItemAndItsMetadata() {
        load(item(1, "第 1 回", "番組 A"), item(2, "第 2 回", "番組 A"))
        assertEquals(NowPlayingState(EpisodeId(1), "第 1 回", "番組 A", isPlaying = false, durationMs = 1_800_000), nowPlaying.state.value)
        assertEquals(EpisodeId(1), nowPlaying.excludedFromSync)
    }

    @Test
    fun isPlayingFollowsTheReadyAndPlayWhenReadyState() {
        load(item(1, "第 1 回", "番組 A"))
        player.update { setPlaybackState(Player.STATE_READY) }
        player.playWhenReady = true
        player.update { }
        assertEquals(true, nowPlaying.state.value?.isPlaying)

        player.playWhenReady = false
        player.update { }
        assertEquals(false, nowPlaying.state.value?.isPlaying)
        assertEquals(EpisodeId(1), nowPlaying.state.value?.episodeId)
    }

    @Test
    fun movingToTheNextItemFollows() {
        load(item(1, "第 1 回", "番組 A"), item(2, "第 2 回", "番組 A"))
        player.seekToNextMediaItem()
        player.update { }
        assertEquals(EpisodeId(2), nowPlaying.state.value?.episodeId)
        assertEquals("第 2 回", nowPlaying.state.value?.title)
        assertEquals(EpisodeId(2), nowPlaying.excludedFromSync)
    }

    /** 聴き終えた回は載ったまま（ミニプレイヤーは残る）だが、同期の削除除外からは外れる（#27）。 */
    @Test
    fun endedKeepsTheItemLoadedButReleasesItToSync() {
        load(item(1, "第 1 回", "番組 A"))
        player.update { setPlaybackState(Player.STATE_READY) }
        player.playWhenReady = true
        player.update { }
        assertEquals(EpisodeId(1), nowPlaying.excludedFromSync)
        player.update { setPlaybackState(Player.STATE_ENDED) }
        assertEquals(
            NowPlayingState(EpisodeId(1), "第 1 回", "番組 A", isPlaying = false, isEnded = true, durationMs = 1_800_000),
            nowPlaying.state.value,
        )
        assertNull(nowPlaying.excludedFromSync)
    }

    /** スリープタイマーの「この回の終わりまで」で途中の回を止めたとき（#36）も聴き終えた扱い。再開すれば戻る。 */
    @Test
    fun pausedAtEndOfItemCountsAsEnded() {
        load(item(1, "第 1 回", "番組 A"), item(2, "第 2 回", "番組 A"))
        player.update { setPlaybackState(Player.STATE_READY) }
        player.playWhenReady = true
        player.update { }
        player.update { setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) }
        assertEquals(true, nowPlaying.state.value?.isEnded)
        assertNull(nowPlaying.excludedFromSync)
        player.update { setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        assertEquals(false, nowPlaying.state.value?.isEnded)
        assertEquals(EpisodeId(1), nowPlaying.excludedFromSync)
    }

    /** 止まっていてもシークで位置が変わる（ミニプレイヤーの線、#59）。 */
    @Test
    fun seekingUpdatesThePositionWhilePaused() {
        load(item(1, "第 1 回", "番組 A"))
        player.update { setPlaybackState(Player.STATE_READY) }
        assertEquals(0L, nowPlaying.state.value?.positionMs)
        player.seekTo(600_000)
        assertEquals(600_000L, nowPlaying.state.value?.positionMs)
        assertEquals(1_800_000L, nowPlaying.state.value?.durationMs)
    }
    /** 「回の終わりまで」で止めた後に次の回へシークしても、新しい回に聴き終えた印が一瞬でも付かない（#59 で onPositionDiscontinuity を読むようにした）。 */
    @Test
    fun seekingToTheNextItemDropsPausedAtEndOfItem() = runTest {
        load(item(1, "第 1 回", "番組 A"), item(2, "第 2 回", "番組 A"))
        player.update { setPlaybackState(Player.STATE_READY) }
        player.playWhenReady = true
        player.update { }
        player.update { setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) }
        assertEquals(true, nowPlaying.state.value?.isEnded)
        // 一瞬の値も拾うため StateFlow を購読する（onPositionDiscontinuity → onMediaItemTransition の間）
        val seen = mutableListOf<NowPlayingState?>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { nowPlaying.state.collect { seen += it } }
        player.seekToNextMediaItem()
        collector.cancel()
        assertEquals(EpisodeId(2), nowPlaying.state.value?.episodeId)
        assertEquals(false, nowPlaying.state.value?.isEnded)
        assertEquals(emptyList<NowPlayingState?>(), seen.filter { it?.episodeId == EpisodeId(2) && it.isEnded })
    }
    @Test
    fun clearingThePlaylistClearsNowPlaying() {
        load(item(1, "第 1 回", "番組 A"))
        player.update { setPlaylist(emptyList()) }
        assertNull(nowPlaying.state.value)
        assertNull(nowPlaying.excludedFromSync)
    }

    @Test
    fun serviceShutdownClearsExplicitly() {
        load(item(1, "第 1 回", "番組 A"))
        nowPlaying.set(null)
        assertNull(nowPlaying.state.value)
        assertNull(nowPlaying.excludedFromSync)
    }
}
