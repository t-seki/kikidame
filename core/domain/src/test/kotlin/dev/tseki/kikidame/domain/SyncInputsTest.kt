package dev.tseki.kikidame.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SyncInputsTest {
    private fun program(id: Long, source: String?, syncEnabled: Boolean = false, rule: RetentionRule = RetentionRule()) = Program(
        id = ProgramId(id),
        sourceItemId = source?.let(::SourceItemId),
        name = "番組 $id",
        publisherName = null,
        syncEnabled = syncEnabled,
        retentionRule = rule,
    )

    private fun local(id: Long, source: String?) = LocalEpisodeState(
        id = EpisodeId(id),
        sourceItemId = source?.let(::SourceItemId),
        publishedAt = Instant.parse("2026-09-01T00:00:00Z"),
        title = "回 $id",
        pinned = false,
        played = false,
        hasLocalFile = false,
    )

    private fun sourceProgram(id: String) = SourceProgram(SourceItemId(id), name = id, publisherName = null)

    private fun sourceEpisode(id: String, program: String) = SourceEpisode(
        sourceId = SourceItemId(id),
        programSourceId = SourceItemId(program),
        title = id,
        publishedAt = Instant.parse("2026-09-01T00:00:00Z"),
        addedAt = null,
        runtime = 30.minutes,
        sizeBytes = null,
        container = "mp3",
    )

    private fun ids(vararg v: String) = v.map(::SourceItemId).toSet()

    @Test
    fun `library scope takes every program with a source id and copies its settings and local episodes`() {
        val rule = RetentionRule(keepLatest = 2, deleteAfterPlayed = true)
        val programs = listOf(program(1, "p1", syncEnabled = true, rule = rule), program(2, "p2"))
        val locals = mapOf(ProgramId(1) to listOf(local(10, "e1"), local(11, null)))
        val snapshot = SourceSnapshot(
            programs = listOf(sourceProgram("p1"), sourceProgram("p2")),
            episodes = listOf(sourceEpisode("e1", "p1"), sourceEpisode("e2", "p1")),
        )

        val inputs = SyncInputs.of(programs, locals, snapshot)

        assertEquals(
            listOf(
                SyncProgramInput(ProgramId(1), true, rule, SourceEpisodes.Known(ids("e1", "e2")), listOf(local(10, "e1"), local(11, null))),
                // 番組一覧にあって各回が無い番組は「一覧が空」（Known(emptySet())）
                SyncProgramInput(ProgramId(2), false, RetentionRule(), SourceEpisodes.Known(emptySet()), emptyList()),
            ),
            inputs,
        )
    }

    @Test
    fun `program scope takes only the program with that source id and does not mark the others gone`() {
        val programs = listOf(program(1, "p1"), program(2, "p2"), program(3, "p3"))
        val snapshot = SourceSnapshot(
            programs = listOf(sourceProgram("p2")),
            episodes = listOf(sourceEpisode("e1", "p2")),
            scope = SnapshotScope.Program(SourceItemId("p2")),
        )

        val inputs = SyncInputs.of(programs, emptyMap(), snapshot)

        assertEquals(listOf(ProgramId(2)), inputs.map { it.programId })
        assertEquals(SourceEpisodes.Known(ids("e1")), inputs.single().source)
    }

    @Test
    fun `program scope for a program not held locally gives no input`() {
        val snapshot = SourceSnapshot(
            programs = listOf(sourceProgram("p9")),
            episodes = emptyList(),
            scope = SnapshotScope.Program(SourceItemId("p9")),
        )

        assertTrue(SyncInputs.of(listOf(program(1, "p1")), emptyMap(), snapshot).isEmpty())
    }

    @Test
    fun `programs without a source id are not inputs in either scope`() {
        val programs = listOf(program(1, null), program(2, "p2"))
        val library = SourceSnapshot(programs = listOf(sourceProgram("p2")), episodes = emptyList())
        val single = library.copy(scope = SnapshotScope.Program(SourceItemId("p2")))

        assertEquals(listOf(ProgramId(2)), SyncInputs.of(programs, emptyMap(), library).map { it.programId })
        assertEquals(listOf(ProgramId(2)), SyncInputs.of(programs, emptyMap(), single).map { it.programId })
    }

    @Test
    fun `program whose source id is missing from the library snapshot is gone`() {
        val programs = listOf(program(1, "p1"), program(2, "p2"))
        val snapshot = SourceSnapshot(programs = listOf(sourceProgram("p1")), episodes = emptyList())

        val inputs = SyncInputs.of(programs, mapOf(ProgramId(2) to listOf(local(20, "e20"))), snapshot)

        assertEquals(SourceEpisodes.Known(emptySet()), inputs[0].source)
        assertEquals(SourceEpisodes.Gone, inputs[1].source)
        assertEquals(listOf(local(20, "e20")), inputs[1].local)
    }
}
