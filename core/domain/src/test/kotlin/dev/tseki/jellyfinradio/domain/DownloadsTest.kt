package dev.tseki.jellyfinradio.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class DownloadsTest {
    private fun episode(server: String?) = Episode(
        id = EpisodeId(1),
        serverItemId = server?.let(::ServerItemId),
        programId = ProgramId(1),
        title = "x",
        airedAt = Instant.parse("2026-09-16T15:00:00Z"),
        addedAt = null,
        runtime = 30.minutes,
        sizeBytes = 0,
        container = "m4a",
    )

    @Test
    fun `server episodes lose only the file, seeded ones lose the row`() {
        assertEquals(LocalDeletionScope.FILE_ONLY, deletionScopeFor(episode("abc")))
        assertEquals(LocalDeletionScope.EPISODE, deletionScopeFor(episode(null)))
    }

    @Test
    fun `path mirrors the seed layout and sanitises forbidden characters`() {
        assertEquals(
            "TBSラジオ/パンサー向井のふらっと/2026-09-16 (1).m4a",
            EpisodeFileName.relativePath("TBSラジオ", "パンサー向井のふらっと", "2026-09-16 (1)", "m4a"),
        )
        assertEquals("_/A_B/what_.m4a", EpisodeFileName.relativePath(null, "A/B", "what?", "M4A"))
        assertEquals("_/p/t.m4a", EpisodeFileName.relativePath(null, "p", "t", ""))
        assertEquals("s/p/trailing dot.mp3", EpisodeFileName.relativePath("s", "p", "trailing dot.", "mp3"))
    }

    @Test
    fun `container lists from ffmpeg collapse to a known audio extension`() {
        assertEquals("m4a", EpisodeFileName.extensionFor("mov,mp4,m4a,3gp,3g2,mj2"))
        assertEquals("mp3", EpisodeFileName.extensionFor("MP3"))
        assertEquals("mkv", EpisodeFileName.extensionFor("mkv,webm"))
        assertEquals("m4a", EpisodeFileName.extensionFor(""))
        assertEquals("s/p/t.m4a", EpisodeFileName.relativePath("s", "p", "t", "mov,mp4,m4a,3gp,3g2,mj2"))
    }
    @Test
    fun `collision suffix goes before the extension`() {
        assertEquals("s/p/t (2).m4a", EpisodeFileName.withSuffix("s/p/t.m4a", 2))
        assertEquals("s/p/noext (3)", EpisodeFileName.withSuffix("s/p/noext", 3))
    }
}
