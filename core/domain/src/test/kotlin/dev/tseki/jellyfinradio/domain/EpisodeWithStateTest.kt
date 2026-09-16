package dev.tseki.jellyfinradio.domain
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
class EpisodeWithStateTest {
    private val now = Instant.parse("2026-09-16T00:00:00Z")
    private val episode = Episode(
        id = EpisodeId(1),
        serverItemId = null,
        programId = ProgramId(1),
        title = "X 2026-06-12",
        airedAt = now,
        addedAt = null,
        runtime = 30.minutes,
        sizeBytes = 0,
        container = "m4a",
    )
    private fun withPlayback(position: Duration, played: Boolean) = EpisodeWithState(
        episode = episode,
        localFile = null,
        playback = PlaybackState(episode.id, position, played, updatedAt = now),
    )
    @Test
    fun `no record means no resume position`() {
        assertNull(EpisodeWithState(episode, null, null).resumePosition)
    }
    @Test
    fun `mid-way position is shown regardless of played flag`() {
        assertEquals(10.minutes, withPlayback(10.minutes, played = false).resumePosition)
        assertEquals(10.minutes, withPlayback(10.minutes, played = true).resumePosition)
    }
    @Test
    fun `start and near-end positions restart from the beginning so nothing is shown`() {
        assertNull(withPlayback(Duration.ZERO, played = false).resumePosition)
        assertNull(withPlayback(29.minutes, played = true).resumePosition)
        assertNull(withPlayback(30.minutes, played = true).resumePosition)
    }
}
