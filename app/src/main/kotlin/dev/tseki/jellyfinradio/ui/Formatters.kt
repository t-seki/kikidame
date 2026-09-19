package dev.tseki.jellyfinradio.ui
import dev.tseki.jellyfinradio.domain.AiredAt
import kotlinx.datetime.LocalDate
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
fun Instant.toAiredDateText(): String = toLocalDateTime(AiredAt.ZONE).date.toString()
/**
 * 番組一覧の「最新」用の短い日付（#41）。今年なら MM-DD、それ以外は YYYY-MM-DD（古い番組は年が要る）。
 * 各回一覧の放送日は [toAiredDateText] のまま（一覧内で年をまたぐので省略しない）。
 */
fun Instant.toLatestDateText(today: LocalDate = Clock.System.todayIn(AiredAt.ZONE)): String {
    val date = toLocalDateTime(AiredAt.ZONE).date
    return if (date.year == today.year) "%02d-%02d".format(date.monthNumber, date.dayOfMonth) else date.toString()
}
fun Instant.toDateTimeText(): String = toLocalDateTime(AiredAt.ZONE).let { dt ->
    "%s %02d:%02d".format(dt.date, dt.hour, dt.minute)
}
fun Duration.toClockText(): String = toComponents { hours, minutes, seconds, _ ->
    if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
