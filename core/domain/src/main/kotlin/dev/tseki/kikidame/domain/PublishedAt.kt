package dev.tseki.kikidame.domain

import kotlinx.datetime.TimeZone

/** 公開日の置き場所。 */
object PublishedAt {
    /** 録音は日本のラジオなので、日付だけの公開日は JST の 0 時に置く。 */
    val ZONE: TimeZone = TimeZone.of("Asia/Tokyo")
}
