package dev.tseki.kikidame.sync

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 裏の同期が失敗で終わったときに、同期の失敗の通知を出すかの判断（#346 の決定 5・7）。 */
class SyncFailureNotificationTest {
    private val now = Instant.parse("2026-10-26T05:00:00Z")

    @Test
    fun theThresholdIs24Hours() {
        assertEquals(24.hours, SyncWorker.NOTIFY_AFTER)
    }

    @Test
    fun notifiesWhenTheLastSuccessIs24HoursOrMoreAgo() {
        assertTrue(SyncWorker.shouldNotifyFailure(now - 24.hours, notified = false, now = now))
        assertTrue(SyncWorker.shouldNotifyFailure(now - 72.hours, notified = false, now = now))
    }

    @Test
    fun doesNotNotifyWithinTheFirst24Hours() {
        assertFalse(SyncWorker.shouldNotifyFailure(now - 24.hours + 1.minutes, notified = false, now = now))
    }

    /** 一度出したら、成功するまで（利用者が払っても）出し直さない。 */
    @Test
    fun doesNotNotifyAgainUntilASuccess() {
        assertFalse(SyncWorker.shouldNotifyFailure(now - 72.hours, notified = true, now = now))
    }

    /** 一度も成功していない（接続した直後の取り込み）ときは出さない。 */
    @Test
    fun doesNotNotifyBeforeTheFirstSuccess() {
        assertFalse(SyncWorker.shouldNotifyFailure(null, notified = false, now = now))
    }
}
