package dev.tseki.jellyfinradio.domain
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
class PlaybackRulesTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val later = now + 10.seconds
    private val episode = EpisodeId(1)
    private val runtime = 30.minutes
    @Test
    fun `not played until within two minutes of the end`() {
        assertFalse(PlaybackRules.isNearEnd(27.minutes + 59.seconds, runtime))
        assertTrue(PlaybackRules.isNearEnd(28.minutes, runtime))
        assertTrue(PlaybackRules.isNearEnd(runtime, runtime))
    }
    @Test
    fun `short episode is played once playback has progressed at all`() {
        val short = 90.seconds
        assertFalse(PlaybackRules.isNearEnd(Duration.ZERO, short))
        assertTrue(PlaybackRules.isNearEnd(1.seconds, short))
    }
    @Test
    fun `advance sets played near the end and keeps it when seeking back`() {
        val initial = PlaybackState.initial(episode, now)
        val nearEnd = PlaybackRules.advance(initial, 29.minutes, runtime, now)
        assertTrue(nearEnd.played)
        assertEquals(29.minutes, nearEnd.position)
        val seekedBack = PlaybackRules.advance(nearEnd, 1.minutes, runtime, later)
        assertTrue(seekedBack.played, "played must not auto-reset")
        assertEquals(1.minutes, seekedBack.position)
        assertEquals(later, seekedBack.updatedAt)
    }
    @Test
    fun `advance marks the record dirty`() {
        val synced = PlaybackState(episode, 1.minutes, played = false, updatedAt = now, syncedAt = now)
        val advanced = PlaybackRules.advance(synced, 2.minutes, runtime, later)
        assertNull(advanced.syncedAt)
    }
    @Test
    fun `manual toggle changes played but not position`() {
        val state = PlaybackState(episode, 10.minutes, played = true, updatedAt = now)
        val unplayed = PlaybackRules.setPlayed(state, played = false, now = later)
        assertFalse(unplayed.played)
        assertEquals(10.minutes, unplayed.position)
        assertNull(unplayed.syncedAt)
    }
    @Test
    fun `resume from saved position unless near the end`() {
        assertEquals(10.minutes, PlaybackRules.resumePosition(10.minutes, runtime))
        assertEquals(Duration.ZERO, PlaybackRules.resumePosition(29.minutes, runtime))
        assertEquals(Duration.ZERO, PlaybackRules.resumePosition(runtime, runtime))
        assertEquals(Duration.ZERO, PlaybackRules.resumePosition(Duration.ZERO, runtime))
    }
}
