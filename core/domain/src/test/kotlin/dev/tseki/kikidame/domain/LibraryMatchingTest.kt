package dev.tseki.kikidame.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LibraryMatchingTest {
    private val now = Instant.parse("2026-09-17T00:00:00Z")

    private fun sp(id: String, name: String, station: String? = "TBSラジオ") =
        ServerProgram(ServerItemId(id), name, station)

    private fun se(id: String, program: String, title: String, aired: Instant = now, runtime: Duration = 30.minutes) = ServerEpisode(
        serverId = ServerItemId(id),
        programServerId = ServerItemId(program),
        title = title,
        airedAt = aired,
        addedAt = now,
        runtime = runtime,
        sizeBytes = 1,
        container = "m4a",
    )
    private val day1 = Instant.parse("2026-09-15T15:00:00Z")
    private val day2 = Instant.parse("2026-09-16T15:00:00Z")

    @Test
    fun `server rows that are already linked do not make a name ambiguous`() {
        val match = LibraryMatching.match(
            localPrograms = listOf(lp(1, "dup", server = "P1"), lp(2, "dup")),
            localEpisodes = listOf(le(20, 2, "x"), le(10, 1, "x", server = "E1")),
            server = ServerSnapshot(
                programs = listOf(sp("P1", "dup"), sp("P2", "dup")),
                episodes = listOf(se("E1", "P1", "x"), se("E2", "P2", "x")),
            ),
        )
        assertEquals(mapOf(ProgramId(2) to ServerItemId("P2")), match.programLinks)
        assertEquals(mapOf(EpisodeId(20) to ServerItemId("E2")), match.episodeLinks)
        assertTrue(match.newPrograms.isEmpty() && match.newEpisodes.isEmpty())
    }

    private fun lp(id: Long, name: String, station: String? = "TBSラジオ", server: String? = null) =
        LocalProgramKey(ProgramId(id), server?.let(::ServerItemId), station, name)

    private fun le(id: Long, program: Long, title: String, server: String? = null, aired: Instant = now, runtime: Duration = Duration.ZERO) =
        LocalEpisodeKey(EpisodeId(id), server?.let(::ServerItemId), ProgramId(program), title, aired, runtime)

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

    // --- 未結合の結び直し（ADR 0005） ---
    @Test
    fun `a program whose server id vanished is relinked by name and its episodes by title`() {
        val match = LibraryMatching.match(
            localPrograms = listOf(lp(1, "ふらっと", server = "OLD-P")),
            localEpisodes = listOf(le(10, 1, "2026-09-14 (1)", server = "OLD-E1"), le(11, 1, "gone for real", server = "OLD-E2")),
            server = ServerSnapshot(listOf(sp("NEW-P", "ふらっと")), listOf(se("NEW-E1", "NEW-P", "2026-09-14 (1)"), se("NEW-E3", "NEW-P", "2026-09-15 (1)"))),
        )
        assertEquals(mapOf(ProgramId(1) to ServerItemId("NEW-P")), match.programLinks)
        assertTrue(match.newPrograms.isEmpty(), "no duplicate program row")
        assertEquals(mapOf(EpisodeId(10) to ServerItemId("NEW-E1")), match.episodeLinks)
        assertEquals(listOf("NEW-E3"), match.newEpisodes.map { it.serverId.value })
        // OLD-E2 は結び直せないので、そのまま同期で「サーバから消えた」として扱われる
    }
    @Test
    fun `episode ids can churn while the program id stays`() {
        val match = LibraryMatching.match(
            localPrograms = listOf(lp(1, "ふらっと", server = "P")),
            localEpisodes = listOf(le(10, 1, "a", server = "OLD-A"), le(11, 1, "b", server = "OLD-B")),
            server = ServerSnapshot(listOf(sp("P", "ふらっと")), listOf(se("NEW-A", "P", "a"), se("NEW-B", "P", "b"))),
        )
        assertTrue(match.programLinks.isEmpty())
        assertEquals(mapOf(EpisodeId(10) to ServerItemId("NEW-A"), EpisodeId(11) to ServerItemId("NEW-B")), match.episodeLinks)
        assertTrue(match.newEpisodes.isEmpty())
    }
    @Test
    fun `two stale programs with the same name are ambiguous and stay unlinked`() {
        val match = LibraryMatching.match(
            localPrograms = listOf(lp(1, "dup", server = "OLD-1"), lp(2, "dup", server = "OLD-2")),
            localEpisodes = emptyList(),
            server = ServerSnapshot(listOf(sp("NEW", "dup")), emptyList()),
        )
        assertTrue(match.programLinks.isEmpty())
        assertEquals(listOf("NEW"), match.newPrograms.map { it.serverId.value })
    }
    @Test
    fun `a stale id that is still on the server is not a candidate`() {
        // 手元の行 1 は P を持ち、P はサーバに在る → 結び付いている。同名の未結合行 2 が NEW と結ばれる
        val match = LibraryMatching.match(
            localPrograms = listOf(lp(1, "dup", server = "P"), lp(2, "dup")),
            localEpisodes = emptyList(),
            server = ServerSnapshot(listOf(sp("P", "dup"), sp("NEW", "dup")), emptyList()),
        )
        assertEquals(mapOf(ProgramId(2) to ServerItemId("NEW")), match.programLinks)
    }
    @Test
    fun `a program-scoped snapshot relinks only that program's episodes`() {
        val match = LibraryMatching.match(
            localPrograms = listOf(lp(1, "ふらっと", server = "P"), lp(2, "other", server = "OLD-Q")),
            localEpisodes = listOf(le(10, 1, "a", server = "OLD-A"), le(20, 2, "a", server = "OLD-QA")),
            server = ServerSnapshot(listOf(sp("P", "ふらっと")), listOf(se("NEW-A", "P", "a")), scope = SnapshotScope.Program(ServerItemId("P"))),
        )
        assertTrue(match.programLinks.isEmpty(), "programs are not relinked without the program list")
        assertTrue(match.newPrograms.isEmpty(), "program 2 is not declared new either")
        assertEquals(mapOf(EpisodeId(10) to ServerItemId("NEW-A")), match.episodeLinks)
    }
    @Test
    fun `a program-scoped snapshot does not relink a stale program by name`() {
        val match = LibraryMatching.match(
            localPrograms = listOf(lp(1, "ふらっと", server = "OLD-P")),
            localEpisodes = emptyList(),
            server = ServerSnapshot(listOf(sp("NEW-P", "ふらっと")), emptyList(), scope = SnapshotScope.Program(ServerItemId("NEW-P"))),
        )
        assertTrue(match.programLinks.isEmpty())
        assertEquals(listOf("NEW-P"), match.newPrograms.map { it.serverId.value })
    }
    // --- 第二段: 放送日 ＋ 尺（#9） ---
    @Test
    fun `seeded episodes with different titles link by aired day and runtime`() {
        val match = LibraryMatching.match(
            localPrograms = listOf(lp(1, "ふらっと", server = "P")),
            localEpisodes = listOf(
                le(10, 1, "ふらっと 2026-09-16-1", aired = day2, runtime = 89.minutes + 59.seconds),
                le(11, 1, "ふらっと 2026-09-16-2", aired = day2, runtime = 60.minutes + 5.seconds),
                le(12, 1, "ふらっと 2026-09-15-1", aired = day1, runtime = 90.minutes),
            ),
            server = ServerSnapshot(
                listOf(sp("P", "ふらっと")),
                listOf(
                    se("E1", "P", "2026-09-16 (1)", aired = day2, runtime = 90.minutes),
                    se("E2", "P", "2026-09-16 (2)", aired = day2, runtime = 60.minutes + 4.seconds),
                    se("E3", "P", "2026-09-15 (1)", aired = day1, runtime = 90.minutes + 2.seconds),
                ),
            ),
        )
        assertEquals(
            mapOf(EpisodeId(10) to ServerItemId("E1"), EpisodeId(11) to ServerItemId("E2"), EpisodeId(12) to ServerItemId("E3")),
            match.episodeLinks,
        )
        assertTrue(match.newEpisodes.isEmpty())
    }
    @Test
    fun `same day with indistinguishable runtimes is not linked`() {
        val match = LibraryMatching.match(
            localPrograms = listOf(lp(1, "ふらっと", server = "P")),
            localEpisodes = listOf(le(10, 1, "x-1", aired = day2, runtime = 60.minutes), le(11, 1, "x-2", aired = day2, runtime = 60.minutes + 2.seconds)),
            server = ServerSnapshot(
                listOf(sp("P", "ふらっと")),
                listOf(se("E1", "P", "(1)", aired = day2, runtime = 60.minutes), se("E2", "P", "(2)", aired = day2, runtime = 60.minutes + 1.seconds)),
            ),
        )
        assertTrue(match.episodeLinks.isEmpty())
        assertEquals(2, match.newEpisodes.size)
    }
    @Test
    fun `runtime beyond the tolerance or unknown runtime is not linked`() {
        val match = LibraryMatching.match(
            localPrograms = listOf(lp(1, "ふらっと", server = "P")),
            localEpisodes = listOf(le(10, 1, "far", aired = day1, runtime = 60.minutes), le(11, 1, "unknown", aired = day2)),
            server = ServerSnapshot(
                listOf(sp("P", "ふらっと")),
                listOf(se("E1", "P", "(1)", aired = day1, runtime = 60.minutes + 6.seconds), se("E2", "P", "(2)", aired = day2, runtime = 30.minutes)),
            ),
        )
        assertTrue(match.episodeLinks.isEmpty())
        assertFalse(match.newEpisodes.isEmpty())
    }
    @Test
    fun `title match wins over aired-day match`() {
        val match = LibraryMatching.match(
            localPrograms = listOf(lp(1, "ふらっと", server = "P")),
            localEpisodes = listOf(le(10, 1, "(1)", aired = day2, runtime = 60.minutes)),
            server = ServerSnapshot(
                listOf(sp("P", "ふらっと")),
                listOf(se("E1", "P", "(1)", aired = day1, runtime = 60.minutes), se("E2", "P", "(2)", aired = day2, runtime = 60.minutes)),
            ),
        )
        assertEquals(mapOf(EpisodeId(10) to ServerItemId("E1")), match.episodeLinks)
        assertEquals(listOf("E2"), match.newEpisodes.map { it.serverId.value })
    }
}
