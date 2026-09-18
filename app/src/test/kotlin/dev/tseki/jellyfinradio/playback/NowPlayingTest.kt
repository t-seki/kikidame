package dev.tseki.jellyfinradio.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.jellyfinradio.domain.EpisodeId
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class NowPlayingTest {
    private val player = FakePlayer()
    private val nowPlaying = NowPlaying().also { player.addListener(it.listener(player)) }

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
        assertEquals(NowPlayingState(EpisodeId(1), "第 1 回", "番組 A", isPlaying = false), nowPlaying.state.value)
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
            NowPlayingState(EpisodeId(1), "第 1 回", "番組 A", isPlaying = false, isEnded = true),
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
