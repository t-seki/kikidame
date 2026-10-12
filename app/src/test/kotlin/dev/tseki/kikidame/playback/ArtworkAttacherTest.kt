package dev.tseki.kikidame.playback
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.domain.EpisodeId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ArtworkAttacherTest {
    private val player = FakePlayer()
    private fun item(id: Long, artwork: ByteArray? = null) = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri("file:///tmp/$id.m4a")
        .setMediaMetadata(MediaMetadata.Builder().setTitle("t$id").apply { artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }.build())
        .build()

    private fun artworkAt(index: Int) = player.getMediaItemAt(index).mediaMetadata.artworkData

    @Test
    fun attachesTheEpisodesImageToTheCurrentItemOnly() = runTest(StandardTestDispatcher()) {
        val loaded = mutableListOf<EpisodeId>()
        player.setPlaylist(listOf(item(1), item(2)), listOf(1000, 1000))
        ArtworkAttacher(player, this) { id -> loaded += id; byteArrayOf(id.value.toByte()) }.attach()
        player.seekToNextMediaItem()
        player.update { }
        runCurrent()
        assertEquals(listOf(EpisodeId(2)), loaded)
        assertNull(artworkAt(0))
        assertContentEquals(byteArrayOf(2), artworkAt(1))
        assertEquals("t2", player.getMediaItemAt(1).mediaMetadata.title)
        assertEquals(1, player.currentMediaItemIndex)
    }

    @Test
    fun leavesTheItemAloneWhenThereIsNoImage() = runTest(StandardTestDispatcher()) {
        player.setPlaylist(listOf(item(1), item(2)), listOf(1000, 1000))
        ArtworkAttacher(player, this) { null }.attach()
        player.seekToNextMediaItem()
        player.update { }
        runCurrent()
        assertNull(artworkAt(1))
    }

    @Test
    fun doesNotLoadAgainWhenTheItemAlreadyHasAnImage() = runTest(StandardTestDispatcher()) {
        var calls = 0
        player.setPlaylist(listOf(item(1), item(2, byteArrayOf(9))), listOf(1000, 1000))
        ArtworkAttacher(player, this) { calls++; byteArrayOf(1) }.attach()
        player.seekToNextMediaItem()
        player.update { }
        runCurrent()
        assertEquals(0, calls)
        assertContentEquals(byteArrayOf(9), artworkAt(1))
    }

    @Test
    fun dropsTheImageWhenAnotherEpisodeIsCurrentByThen() = runTest(StandardTestDispatcher()) {
        player.setPlaylist(listOf(item(1), item(2), item(3)), listOf(1000, 1000, 1000))
        ArtworkAttacher(player, this) { id ->
            if (id == EpisodeId(2)) player.seekToNextMediaItem()
            byteArrayOf(id.value.toByte())
        }.attach()
        player.seekToNextMediaItem()
        player.update { }
        runCurrent()
        player.update { }
        runCurrent()
        assertNull(artworkAt(1))
    }
}
