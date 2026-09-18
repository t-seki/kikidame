package dev.tseki.jellyfinradio.domain

import kotlinx.datetime.TimeZone

/** 放送日の置き場所。 */
object AiredAt {
    /** 録音は日本のラジオなので、日付だけの放送日は JST の 0 時に置く。 */
    val ZONE: TimeZone = TimeZone.of("Asia/Tokyo")
}
