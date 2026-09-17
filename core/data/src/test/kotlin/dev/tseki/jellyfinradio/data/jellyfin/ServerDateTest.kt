package dev.tseki.jellyfinradio.data.jellyfin

import kotlinx.datetime.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ServerDateTest {
    @Test
    fun dateCreatedBeforeNineJstFallsOnTheJstDay() {
        // JST 2026-09-17 01:00 に取り込まれた回。サーバは UTC で 2026-09-16T16:00 を返す
        val created = java.time.LocalDateTime.of(2026, 9, 16, 16, 0)
        assertEquals(LocalDate(2026, 9, 17), created.toKotlinLocalDate())
        assertEquals(Instant.parse("2026-09-16T16:00:00Z"), created.toInstantUtc())
    }

    @Test
    fun premiereDateAtUtcMidnightKeepsItsDate() {
        // タグ由来の PremiereDate は日付だけなので UTC 0 時で来る。JST では同じ日の 9:00
        val premiere = java.time.LocalDateTime.of(2026, 9, 16, 0, 0)
        assertEquals(LocalDate(2026, 9, 16), premiere.toKotlinLocalDate())
    }
}
