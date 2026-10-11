package dev.tseki.kikidame.playback
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.SimpleBasePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
@RunWith(AndroidJUnit4::class)
class KnownRuntimeTest {
    private fun item(metadataMs: Long?) = MediaItem.Builder()
        .setMediaId("1")
        .setUri("file:///tmp/1.m4a")
        .setMediaMetadata(MediaMetadata.Builder().setDurationMs(metadataMs).build())
        .build()
    /** [playerDurationUs] が null なら player 側の尺は未設定（[C.TIME_UNSET]）。 */
    private fun playerWith(mediaItem: MediaItem, playerDurationUs: Long?): FakePlayer {
        val player = FakePlayer()
        player.update {
            setPlaylist(
                listOf(
                    SimpleBasePlayer.MediaItemData.Builder("1").setMediaItem(mediaItem).apply {
                        if (playerDurationUs != null) setDurationUs(playerDurationUs)
                    }.build(),
                ),
            )
        }
        return player
    }
    @Test
    fun runtimeOfReadsMetadataOnlyWhenPositive() {
        assertEquals(30.minutes, EpisodeMediaItems.runtimeOf(item(30.minutes.inWholeMilliseconds)))
        assertNull(EpisodeMediaItems.runtimeOf(item(0)))
        assertNull(EpisodeMediaItems.runtimeOf(item(null)))
        assertNull(EpisodeMediaItems.runtimeOf(null))
    }
    @Test
    fun playerDurationWinsWhenPositive() {
        val player = playerWith(item(30.minutes.inWholeMilliseconds), 10.seconds.inWholeMicroseconds)
        assertEquals(10.seconds, player.knownRuntime())
    }
    @Test
    fun fallsBackToMetadataWhenPlayerDurationIsUnset() {
        val player = playerWith(item(30.minutes.inWholeMilliseconds), null)
        assertEquals(C.TIME_UNSET, player.duration)
        assertEquals(30.minutes, player.knownRuntime())
    }
    @Test
    fun fallsBackToMetadataWhenPlayerDurationIsZero() {
        val player = playerWith(item(30.minutes.inWholeMilliseconds), 0)
        assertEquals(0L, player.duration)
        assertEquals(30.minutes, player.knownRuntime())
    }
    @Test
    fun nullWhenNeitherIsKnown() {
        assertNull(playerWith(item(null), null).knownRuntime())
        assertNull(playerWith(item(0), 0).knownRuntime())
    }
}
