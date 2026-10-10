package dev.tseki.kikidame.data.jellyfin

import dev.tseki.kikidame.domain.PublishedAt
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Instant

/** Jellyfin の日時（`PremiereDate` → `DateCreated`）から公開日を作る。日付部分だけ取り JST 0 時に置く。 */
internal fun jellyfinPublishedAt(premiereDate: LocalDate?, dateCreated: LocalDate): Instant =
    (premiereDate ?: dateCreated).atStartOfDayIn(PublishedAt.ZONE)
