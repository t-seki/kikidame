package dev.tseki.jellyfinradio.domain
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
class EpisodeOrderTest {
    private fun episode(id: Long, airedAt: String, title: String) = Episode(
        id = EpisodeId(id),
        serverItemId = null,
        programId = ProgramId(1),
        title = title,
        airedAt = Instant.parse(airedAt),
        addedAt = null,
        runtime = 30.minutes,
        sizeBytes = 0,
        container = "m4a",
    )
    @Test
    fun `orders by aired date, then title, then id`() {
        val part2 = episode(3, "2026-06-11T15:00:00Z", "X 2026-06-12 (1)")
        val part1 = episode(4, "2026-06-11T15:00:00Z", "X 2026-06-12")
        val older = episode(1, "2026-06-04T15:00:00Z", "X 2026-06-05")
        val dupB = episode(9, "2026-06-18T15:00:00Z", "same")
        val dupA = episode(8, "2026-06-18T15:00:00Z", "same")
        val sorted = listOf(dupB, part2, older, dupA, part1).sortedWith(EpisodeOrder)
        assertEquals(listOf(older, part1, part2, dupA, dupB), sorted)
    }
    @Test
    fun `newest first reverses only the aired date`() {
        val part2 = episode(3, "2026-06-11T15:00:00Z", "X 2026-06-12 (1)")
        val part1 = episode(4, "2026-06-11T15:00:00Z", "X 2026-06-12")
        val older = episode(1, "2026-06-04T15:00:00Z", "X 2026-06-05")
        val newer = episode(5, "2026-06-18T15:00:00Z", "X 2026-06-19")
        val sorted = listOf(part2, older, newer, part1).sortedWith(EpisodeOrder.newestFirst)
        assertEquals(listOf(newer, part1, part2, older), sorted)
    }
}
