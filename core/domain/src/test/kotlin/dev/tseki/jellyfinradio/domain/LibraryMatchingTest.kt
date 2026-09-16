package dev.tseki.jellyfinradio.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class LibraryMatchingTest {
    private val now = Instant.parse("2026-09-17T00:00:00Z")

    private fun sp(id: String, name: String, station: String? = "TBSラジオ") =
        ServerProgram(ServerItemId(id), name, station)

    private fun se(id: String, program: String, title: String) = ServerEpisode(
        serverId = ServerItemId(id),
        programServerId = ServerItemId(program),
        title = title,
        airedAt = now,
        addedAt = now,
        runtime = 30.minutes,
        sizeBytes = 1,
        container = "m4a",
    )

    private fun lp(id: Long, name: String, station: String? = "TBSラジオ", server: String? = null) =
        LocalProgramKey(ProgramId(id), server?.let(::ServerItemId), station, name)

    private fun le(id: Long, program: Long, title: String, server: String? = null) =
        LocalEpisodeKey(EpisodeId(id), server?.let(::ServerItemId), ProgramId(program), title)

    @Test
    fun `links seeded program and episodes by name`() {
        val match = LibraryMatching.match(
            localPrograms = listOf(lp(1, "ふらっと")),
            localEpisodes = listOf(le(10, 1, "ふらっと 2026-09-14-1"), le(11, 1, "ふらっと 2026-09-14-2")),
            server = ServerSnapshot(
                programs = listOf(sp("P", "ふらっと")),
                episodes = listOf(se("E1", "P", "ふらっと 2026-09-14-1"), se("E3", "P", "ふらっと 2026-09-15-1")),
            ),
        )

        assertEquals(mapOf(ProgramId(1) to ServerItemId("P")), match.programLinks)
        assertTrue(match.newPrograms.isEmpty())
        assertEquals(mapOf(EpisodeId(10) to ServerItemId("E1")), match.episodeLinks)
        assertEquals(listOf("E3"), match.newEpisodes.map { it.serverId.value })
    }

    @Test
    fun `station must match too`() {
        val match = LibraryMatching.match(
            localPrograms = listOf(lp(1, "ふらっと", station = "J-WAVE")),
            localEpisodes = emptyList(),
            server = ServerSnapshot(listOf(sp("P", "ふらっと", station = "TBSラジオ")), emptyList()),
        )
        assertTrue(match.programLinks.isEmpty())
        assertEquals(listOf("P"), match.newPrograms.map { it.serverId.value })
    }

    @Test
    fun `rows that already have a server id are left alone`() {
        val match = LibraryMatching.match(
            localPrograms = listOf(lp(1, "ふらっと", server = "P")),
            localEpisodes = listOf(le(10, 1, "x", server = "E1"), le(11, 1, "y")),
            server = ServerSnapshot(listOf(sp("P", "ふらっと")), listOf(se("E1", "P", "renamed"), se("E2", "P", "y"))),
        )
        assertTrue(match.programLinks.isEmpty())
        assertTrue(match.newPrograms.isEmpty())
        assertEquals(mapOf(EpisodeId(11) to ServerItemId("E2")), match.episodeLinks, "episodes of a known program still match")
        assertTrue(match.newEpisodes.isEmpty())
    }

    @Test
    fun `ambiguous candidates on either side are not linked`() {
        val match = LibraryMatching.match(
            localPrograms = listOf(lp(1, "dup"), lp(2, "dup"), lp(3, "solo")),
            localEpisodes = listOf(le(30, 3, "same"), le(31, 3, "same")),
            server = ServerSnapshot(
                programs = listOf(sp("P1", "dup"), sp("S1", "solo"), sp("S2", "solo")),
                episodes = listOf(se("E1", "S1", "same")),
            ),
        )
        assertTrue(match.programLinks.isEmpty(), "two local 'dup' and two server 'solo' are both ambiguous")
        assertEquals(listOf("P1", "S1", "S2"), match.newPrograms.map { it.serverId.value })
        assertTrue(match.episodeLinks.isEmpty())
        assertEquals(listOf("E1"), match.newEpisodes.map { it.serverId.value })
    }

    @Test
    fun `episodes of a new program are new even if a local title matches`() {
        val match = LibraryMatching.match(
            localPrograms = listOf(lp(1, "other")),
            localEpisodes = listOf(le(10, 1, "2026-09-14")),
            server = ServerSnapshot(listOf(sp("P", "fresh")), listOf(se("E1", "P", "2026-09-14"))),
        )
        assertEquals(listOf("P"), match.newPrograms.map { it.serverId.value })
        assertTrue(match.episodeLinks.isEmpty())
        assertEquals(listOf("E1"), match.newEpisodes.map { it.serverId.value })
    }

    @Test
    fun `no spelling normalisation`() {
        val match = LibraryMatching.match(
            localPrograms = listOf(lp(1, "SONYSONPO QUEST")),
            localEpisodes = emptyList(),
            server = ServerSnapshot(listOf(sp("P", "SONY SONPO QUEST")), emptyList()),
        )
        assertTrue(match.programLinks.isEmpty())
    }
}
