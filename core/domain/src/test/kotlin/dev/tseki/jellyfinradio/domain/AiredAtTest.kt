package dev.tseki.jellyfinradio.domain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
class AiredAtTest {
    private val modifiedAt = Instant.fromEpochMilliseconds(1_800_000_000_000)
    private val june12 = LocalDate(2026, 6, 12)
    private val june13 = LocalDate(2026, 6, 13)
    @Test
    fun `tag wins over file name and modified time`() {
        assertEquals(june12.atStartOfDayIn(AiredAt.ZONE), AiredAt.resolve(june12, june13, modifiedAt))
    }
    @Test
    fun `file name is used when the tag is missing`() {
        assertEquals(june13.atStartOfDayIn(AiredAt.ZONE), AiredAt.resolve(null, june13, modifiedAt))
    }
    @Test
    fun `modified time is the last resort`() {
        assertEquals(modifiedAt, AiredAt.resolve(null, null, modifiedAt))
    }
    @Test
    fun `date only values are placed at JST midnight`() {
        // 2026-06-12T00:00+09:00 == 2026-06-11T15:00Z
        assertEquals(Instant.parse("2026-06-11T15:00:00Z"), AiredAt.resolve(june12, null, modifiedAt))
    }
    @Test
    fun `parses radirec style file names`() {
        assertEquals(june12, AiredAt.dateFromFileName("LOGISTEED RADIONOMICS 2026-06-12.m4a"))
        assertEquals(june12, AiredAt.dateFromFileName("LOGISTEED RADIONOMICS 2026-06-12 (1)"))
        assertNull(AiredAt.dateFromFileName("no date here 20260612.m4a"))
        assertNull(AiredAt.dateFromFileName("invalid 2026-13-45.m4a"))
    }
    @Test
    fun `parses tag values with or without time`() {
        assertEquals(june12, AiredAt.dateFromTag("2026-06-12"))
        assertEquals(june12, AiredAt.dateFromTag("2026-06-12T00:00:00Z"))
        assertNull(AiredAt.dateFromTag("2026"))
        assertNull(AiredAt.dateFromTag(null))
    }
}
