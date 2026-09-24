package dev.tseki.kikidame.sync

import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.ServerItemId
import dev.tseki.kikidame.domain.Session
import dev.tseki.kikidame.domain.SessionState
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 起動時同期を積むかの判定（#135）。前回同期と前回の試みの新しい方から 1 時間。 */
class SyncSchedulerTest {
    private val now = Instant.parse("2026-09-24T12:00:00Z")

    private fun ready(lastFetchedAt: Instant?, lastAttemptedAt: Instant?) = SessionState.Ready(
        session = Session("https://jellyfin.lab.example/", "alice", "user-alice", "token"),
        library = SelectedLibrary(ServerItemId("lib-1"), "Radio"),
        lastFetchedAt = lastFetchedAt,
        lastAttemptedAt = lastAttemptedAt,
    )

    @Test
    fun neverSyncedIsStale() {
        assertTrue(SyncScheduler.isStale(ready(null, null), now))
    }

    @Test
    fun aRecentSuccessIsNotStale() {
        assertFalse(SyncScheduler.isStale(ready(now - 30.minutes, now - 30.minutes), now))
        assertTrue(SyncScheduler.isStale(ready(now - 60.minutes, now - 60.minutes), now))
    }

    /** 到達できずに失敗した直後は積まない。1 時間たてば積む。 */
    @Test
    fun aRecentFailedAttemptIsNotStale() {
        val lastSuccess = now - 600.minutes
        assertFalse(SyncScheduler.isStale(ready(lastSuccess, now - 1.minutes), now))
        assertFalse(SyncScheduler.isStale(ready(null, now - 59.minutes), now))
        assertTrue(SyncScheduler.isStale(ready(lastSuccess, now - 60.minutes), now))
    }
}
