package dev.tseki.kikidame.sync

import dev.tseki.kikidame.testing.jellyfinReady
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 起動時同期を積むかの判定。前回同期と前回の試みの新しい方から 15 分。 */
class SyncSchedulerTest {
    private val now = Instant.parse("2026-09-24T12:00:00Z")

    @Test
    fun neverSyncedIsStale() {
        assertTrue(SyncScheduler.isStale(jellyfinReady(null, null), now))
    }

    @Test
    fun aRecentSuccessIsNotStale() {
        assertFalse(SyncScheduler.isStale(jellyfinReady(now - 14.minutes, now - 14.minutes), now))
        assertTrue(SyncScheduler.isStale(jellyfinReady(now - 15.minutes, now - 15.minutes), now))
    }

    /** 到達できずに失敗した直後は積まない。15 分たてば積む。 */
    @Test
    fun aRecentFailedAttemptIsNotStale() {
        val lastSuccess = now - 600.minutes
        assertFalse(SyncScheduler.isStale(jellyfinReady(lastSuccess, now - 1.minutes), now))
        assertFalse(SyncScheduler.isStale(jellyfinReady(null, now - 14.minutes), now))
        assertTrue(SyncScheduler.isStale(jellyfinReady(lastSuccess, now - 15.minutes), now))
    }

    /** 定期は 3 時間、起動時の閾値は 15 分。 */
    @Test
    fun periodAndThreshold() {
        assertEquals(3.hours, SyncScheduler.PERIOD)
        assertEquals(15.minutes, SyncScheduler.STALE_AFTER)
    }
}
