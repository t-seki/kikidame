package dev.tseki.jellyfinradio.ui
import dev.tseki.jellyfinradio.domain.LocalStorageUsage
import org.junit.Test
import kotlin.test.assertEquals
/** 容量の書式（#42）。10 進、GB / MB の 1 桁小数。 */
class SizeTextTest {
    @Test
    fun decimalUnitsWithOneFraction() {
        assertEquals("12.3 GB", 12_345_678_901L.toSizeText())
        assertEquals("1.0 GB", 1_000_000_000L.toSizeText())
        assertEquals("999.9 MB", 999_949_999L.toSizeText())
        // 丸めで 1000.0 MB になる帯は GB に繰り上げる
        assertEquals("1.0 GB", 999_950_000L.toSizeText())
        assertEquals("1.0 GB", 999_999_999L.toSizeText())
        assertEquals("850.0 MB", 850_000_000L.toSizeText())
        assertEquals("0.3 MB", 300_000L.toSizeText())
        assertEquals("0.0 MB", 0L.toSizeText())
    }
    @Test
    fun usageTextIncludesCount() {
        assertEquals("12.3 GB（123 回）", LocalStorageUsage(12_345_678_901L, 123).toText())
    }
}
