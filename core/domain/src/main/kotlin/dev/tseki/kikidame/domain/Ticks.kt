package dev.tseki.kikidame.domain
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
/** Jellyfin / Room 境界で使う 100ns 単位の時間長。ドメイン型では [Duration] を使う。 */
object Ticks {
    private const val NANOS_PER_TICK = 100L
    fun fromDuration(duration: Duration): Long = duration.inWholeNanoseconds / NANOS_PER_TICK
    fun toDuration(ticks: Long): Duration = (ticks * NANOS_PER_TICK).nanoseconds
}
