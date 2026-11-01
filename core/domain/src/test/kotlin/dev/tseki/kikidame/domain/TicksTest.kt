package dev.tseki.kikidame.domain
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
class TicksTest {
    @Test
    fun `one second is ten million ticks`() {
        assertEquals(10_000_000L, Ticks.fromDuration(1.seconds))
        assertEquals(1.seconds, Ticks.toDuration(10_000_000L))
    }
    @Test
    fun `round trips sub-millisecond precision`() {
        val d = 1234.milliseconds
        assertEquals(d, Ticks.toDuration(Ticks.fromDuration(d)))
    }
}
