package dev.tseki.kikidame.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Instant

/** 公開日の置き場所。 */
object PublishedAt {
    /** 録音は日本のラジオなので、日付だけの公開日は JST の 0 時に置く。 */
    val ZONE: TimeZone = TimeZone.of("Asia/Tokyo")

    /**
     * 公開日を作る。取得元に公開日の日付が無ければ [addedDate]（取り込み日時の日付。JST の日付に直したもの）を使い、[ZONE] の 0 時に置く。
     * UTC の日時から JST の日付への変換は呼び出し側（取得元ごと）で行う。
     */
    fun of(date: LocalDate?, addedDate: LocalDate): Instant = (date ?: addedDate).atStartOfDayIn(ZONE)
}
