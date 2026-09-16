package dev.tseki.jellyfinradio.ui
import dev.tseki.jellyfinradio.domain.AiredAt
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant
fun Instant.toAiredDateText(): String = toLocalDateTime(AiredAt.ZONE).date.toString()
fun Duration.toClockText(): String = toComponents { hours, minutes, seconds, _ ->
    if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
