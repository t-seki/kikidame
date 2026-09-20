package dev.tseki.kikidame.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class SyncPlannerTest {
    private fun ep(
        id: Long,
        aired: String,
        server: String? = "s$id",
        pinned: Boolean = false,
        played: Boolean = false,
        local: Boolean = false,
        title: String = "回 $id",
    ) = LocalEpisodeState(
        id = EpisodeId(id),
        serverItemId = server?.let(::ServerItemId),
        airedAt = Instant.parse("${aired}T00:00:00Z"),
        title = title,
        pinned = pinned,
        played = played,
        hasLocalFile = local,
    )

    private fun known(vararg episodes: LocalEpisodeState) =
        ServerEpisodes.Known(episodes.mapNotNull { it.serverItemId }.toSet())

    private fun input(
        vararg episodes: LocalEpisodeState,
        syncEnabled: Boolean = true,
        keepLatest: Int? = 3,
        deleteAfterPlayed: Boolean = false,
        server: ServerEpisodes = known(*episodes),
    ) = SyncProgramInput(
        programId = ProgramId(1),
        syncEnabled = syncEnabled,
        retentionRule = RetentionRule(keepLatest, deleteAfterPlayed),
        server = server,
        local = episodes.toList(),
    )

    private fun ids(vararg v: Long) = v.map(::EpisodeId)

    @Test
    fun `keeps the latest N by aired date and downloads what is missing`() {
        val plan = SyncPlanner.planProgram(
            input(ep(1, "2026-09-01", local = true), ep(2, "2026-09-02"), ep(3, "2026-09-03"), ep(4, "2026-09-04")),
        )
        assertEquals(ids(4, 3, 2), plan.download)
        assertEquals(ids(1), plan.delete)
        assertTrue(plan.remove.isEmpty())
        assertFalse(plan.onHold)
    }

    @Test
    fun `same aired date is ordered by title then id`() {
        val plan = SyncPlanner.planProgram(
            input(
                ep(1, "2026-09-01", title = "2026-09-01"),
                ep(2, "2026-09-01", title = "2026-09-01 (1)"),
                ep(3, "2026-09-01", title = "2026-09-01 (2)"),
                keepLatest = 2,
            ),
        )
        assertEquals(ids(1, 2), plan.download)
    }

    @Test
    fun `pinned episodes are outside the rule and do not count toward N`() {
        val plan = SyncPlanner.planProgram(
            input(
                ep(1, "2026-08-01", pinned = true, local = true),
                ep(2, "2026-08-02", pinned = true, local = true),
                ep(3, "2026-09-01"),
                ep(4, "2026-09-02"),
                ep(5, "2026-09-03"),
                keepLatest = 3,
            ),
        )
        assertEquals(ids(5, 4, 3), plan.download)
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun `delete after played removes played episodes and does not download them`() {
        val plan = SyncPlanner.planProgram(
            input(
                ep(1, "2026-09-01", played = true, local = true),
                ep(2, "2026-09-02", played = true),
                ep(3, "2026-09-03"),
                keepLatest = null,
                deleteAfterPlayed = true,
            ),
        )
        assertEquals(ids(3), plan.download)
        assertEquals(ids(1), plan.delete)
    }

    @Test
    fun `both rules are independent deletion reasons`() {
        // keepLatest=2 → 3,2 が候補。2 は再生済みなので落ちて手元は 3 だけになる（上限であって目標ではない）
        val plan = SyncPlanner.planProgram(
            input(
                ep(1, "2026-09-01", local = true),
                ep(2, "2026-09-02", played = true, local = true),
                ep(3, "2026-09-03", local = true),
                keepLatest = 2,
                deleteAfterPlayed = true,
            ),
        )
        assertTrue(plan.download.isEmpty())
        assertEquals(ids(2, 1), plan.delete.sortedByDescending { it.value })
    }

    @Test
    fun `keepLatest null keeps everything`() {
        val plan = SyncPlanner.planProgram(input(ep(1, "2026-09-01"), ep(2, "2026-09-02"), keepLatest = null))
        assertEquals(ids(2, 1), plan.download)
    }

    @Test
    fun `sync disabled deletes unpinned local files and downloads nothing`() {
        val plan = SyncPlanner.planProgram(
            input(
                ep(1, "2026-09-01", pinned = true, local = true),
                ep(2, "2026-09-02", local = true),
                ep(3, "2026-09-03"),
                syncEnabled = false,
            ),
        )
        assertTrue(plan.download.isEmpty())
        assertEquals(ids(2), plan.delete)
    }

    @Test
    fun `episodes missing from the server list are removed even when pinned`() {
        val e1 = ep(1, "2026-09-01", pinned = true, local = true)
        val e2 = ep(2, "2026-09-02", local = true)
        val e3 = ep(3, "2026-09-03")
        val plan = SyncPlanner.planProgram(input(e1, e2, e3, server = known(e3)))
        assertEquals(ids(1, 2), plan.remove)
        assertEquals(ids(3), plan.download)
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun `removal also applies when sync is disabled`() {
        val e1 = ep(1, "2026-09-01", pinned = true, local = true)
        val plan = SyncPlanner.planProgram(input(e1, syncEnabled = false, server = ServerEpisodes.Known(emptySet())))
        assertEquals(ids(1), plan.remove)
    }

    @Test
    fun `episodes without a server id are never removed`() {
        val seed = ep(1, "2026-09-01", server = null, pinned = true, local = true)
        val plan = SyncPlanner.planProgram(input(seed, server = ServerEpisodes.Known(emptySet())))
        assertTrue(plan.remove.isEmpty())
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun `an unpinned episode without a server id is deleted by the rule`() {
        val seed = ep(1, "2026-09-01", server = null, pinned = false, local = true)
        val plan = SyncPlanner.planProgram(input(seed, ep(2, "2026-09-02"), server = ServerEpisodes.Known(setOf(ServerItemId("s2")))))
        assertEquals(ids(1), plan.delete)
        assertEquals(ids(2), plan.download)
    }

    @Test
    fun `queued and failed rows count as present`() {
        val plan = SyncPlanner.planProgram(input(ep(1, "2026-09-01", local = true), ep(2, "2026-09-02", local = true)))
        assertTrue(plan.download.isEmpty())
    }

    @Test
    fun `unavailable puts the program on hold`() {
        val plan = SyncPlanner.planProgram(
            input(ep(1, "2026-09-01", local = true), ep(2, "2026-09-02"), server = ServerEpisodes.Unavailable),
        )
        assertTrue(plan.onHold)
        assertTrue(plan.download.isEmpty() && plan.delete.isEmpty() && plan.remove.isEmpty())
    }

    @Test
    fun `gone puts the program on hold even with sync disabled`() {
        val plan = SyncPlanner.planProgram(
            input(ep(1, "2026-09-01", local = true), syncEnabled = false, server = ServerEpisodes.Gone),
        )
        assertTrue(plan.onHold)
        assertTrue(plan.delete.isEmpty() && plan.remove.isEmpty())
    }

    @Test
    fun `empty known list is not on hold`() {
        val plan = SyncPlanner.planProgram(input(ep(1, "2026-09-01", local = true), server = ServerEpisodes.Known(emptySet())))
        assertFalse(plan.onHold)
        assertEquals(ids(1), plan.remove)
    }

    @Test
    fun `downloads across programs are ordered newest first`() {
        val a = SyncProgramInput(
            ProgramId(1), true, RetentionRule(),
            ServerEpisodes.Known(setOf(ServerItemId("s1"), ServerItemId("s3"))),
            listOf(ep(1, "2026-09-01"), ep(3, "2026-09-03")),
        )
        val b = SyncProgramInput(
            ProgramId(2), true, RetentionRule(),
            ServerEpisodes.Known(setOf(ServerItemId("s2"), ServerItemId("s4"))),
            listOf(ep(2, "2026-09-02"), ep(4, "2026-09-04")),
        )
        val plan = SyncPlanner.plan(listOf(a, b))
        assertEquals(ids(4, 3, 2, 1), plan.download)
        assertEquals(0, plan.onHoldCount)
    }
}
