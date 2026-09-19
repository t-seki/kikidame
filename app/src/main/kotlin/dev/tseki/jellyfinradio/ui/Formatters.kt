package dev.tseki.jellyfinradio.ui
import dev.tseki.jellyfinradio.domain.AiredAt
import dev.tseki.jellyfinradio.domain.LocalStorageUsage
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
/**
 * ファイル容量（#42）。10 進（1 GB = 1,000,000,000 B。Android の「設定 → ストレージ」と同じ基数）で、
 * 1 GB 以上は GB、それ未満は MB の 1 桁小数。KB は出さない（録音は 1 回 10〜100 MB）。
 */
fun Long.toSizeText(): String =
    if (this >= 1_000_000_000L) "%.1f GB".format(this / 1e9) else "%.1f MB".format(this / 1e6)
/** 「12.3 GB（123 回）」。手元のファイルの合計と回数を並べる。 */
fun LocalStorageUsage.toText(): String = "${totalBytes.toSizeText()}（$episodeCount 回）"
fun Duration.toClockText(): String = toComponents { hours, minutes, seconds, _ ->
    if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
