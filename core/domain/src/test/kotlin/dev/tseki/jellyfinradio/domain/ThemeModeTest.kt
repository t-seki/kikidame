package dev.tseki.jellyfinradio.domain
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
class ThemeModeTest {
    @Test
    fun storageNameRoundTrips() {
        for (mode in ThemeMode.entries) assertEquals(mode, ThemeMode.fromStorageName(mode.name))
    }
    /** 未保存・将来の値・壊れた値は「システム」に丸める（既定に戻るだけで壊れない）。 */
    @Test
    fun unknownOrMissingValueFallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageName(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageName("AMOLED"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageName("dark"))
    }
}
