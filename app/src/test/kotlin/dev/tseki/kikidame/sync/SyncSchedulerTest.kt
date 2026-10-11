package dev.tseki.kikidame.sync

import dev.tseki.kikidame.testing.jellyfinReady
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 起動時同期を積むかの判定（#135）。前回同期と前回の試みの新しい方から 1 時間。 */
class SyncSchedulerTest {
    private val now = Instant.parse("2026-09-24T12:00:00Z")

    @Test
    fun neverSyncedIsStale() {
        assertTrue(SyncScheduler.isStale(jellyfinReady(null, null), now))
    }

    @Test
    fun aRecentSuccessIsNotStale() {
        assertFalse(SyncScheduler.isStale(jellyfinReady(now - 30.minutes, now - 30.minutes), now))
        assertTrue(SyncScheduler.isStale(jellyfinReady(now - 60.minutes, now - 60.minutes), now))
    }

    /** 到達できずに失敗した直後は積まない。1 時間たてば積む。 */
    @Test
    fun aRecentFailedAttemptIsNotStale() {
        val lastSuccess = now - 600.minutes
        assertFalse(SyncScheduler.isStale(jellyfinReady(lastSuccess, now - 1.minutes), now))
        assertFalse(SyncScheduler.isStale(jellyfinReady(null, now - 59.minutes), now))
        assertTrue(SyncScheduler.isStale(jellyfinReady(lastSuccess, now - 60.minutes), now))
    }
}
