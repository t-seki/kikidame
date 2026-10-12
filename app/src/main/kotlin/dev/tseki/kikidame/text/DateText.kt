package dev.tseki.kikidame.text

import dev.tseki.kikidame.domain.PublishedAt
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** 公開日（`YYYY-MM-DD`）。`ui/` の画面と `playback/` の Android Auto の一覧が使うので、どちらにも属さない `text/` に置く。 */
fun Instant.toPublishedDateText(): String = toLocalDateTime(PublishedAt.ZONE).date.toString()
