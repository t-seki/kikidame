package dev.tseki.jellyfinradio.domain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Instant
/** 放送日の決定規則（サーバを経由していない各回向け）。 */
object AiredAt {
    /** 録音は日本のラジオなので、日付だけの放送日は JST の 0 時に置く。 */
    val ZONE: TimeZone = TimeZone.of("Asia/Tokyo")
    private val ISO_DATE_IN_NAME = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    /**
     * タグ → ファイル名の日付 → ファイル更新日時 の順で採用する。
     * [tagDate] / [fileNameDate] は日単位なので JST 0 時の [Instant] にする。
     */
    fun resolve(tagDate: LocalDate?, fileNameDate: LocalDate?, modifiedAt: Instant): Instant =
        (tagDate ?: fileNameDate)?.atStartOfDayIn(ZONE) ?: modifiedAt
    /** ファイル名（拡張子の有無を問わない）から `YYYY-MM-DD` を拾う。無効な日付は無視する。 */
    fun dateFromFileName(fileName: String): LocalDate? =
        ISO_DATE_IN_NAME.findAll(fileName)
            .mapNotNull { m -> runCatching { LocalDate.parse(m.value) }.getOrNull() }
            .firstOrNull()
    /** タグの日付文字列。`YYYY-MM-DD` のほか、先頭がその形式ならそれを取る（`2026-06-12T…` など）。 */
    fun dateFromTag(value: String?): LocalDate? =
        value?.let { dateFromFileName(it) }
}
