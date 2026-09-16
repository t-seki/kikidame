package dev.tseki.jellyfinradio.seed
import dev.tseki.jellyfinradio.domain.AiredAt
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
class LocalLibraryScannerTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val tags = mutableMapOf<String, AudioMetadata>()
    private val reader = object : AudioMetadataReader {
        override fun read(file: File): AudioMetadata = tags[file.name] ?: AudioMetadata(null, null)
    }
    private val scanner = LocalLibraryScanner(reader)
    private fun file(vararg segments: String, mtime: Long = 1_700_000_000_000): File {
        val f = File(tmp.root, segments.joinToString("/"))
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(10))
        f.setLastModified(mtime)
        return f
    }
    @Test
    fun walksStationProgramFileHierarchy() {
        file("J-WAVE", "LOGISTEED RADIONOMICS", "LOGISTEED RADIONOMICS 2026-06-12.m4a")
        file("J-WAVE", "LOGISTEED RADIONOMICS", "LOGISTEED RADIONOMICS 2026-06-12 (1).m4a")
        file("LFR", "ANN", "ANN_20200823.mp3")
        file("LFR", "ANN", "cover.jpg")
        file("stray.m4a")
        file("J-WAVE", "stray-in-station.m4a")
        tags["LOGISTEED RADIONOMICS 2026-06-12.m4a"] = AudioMetadata("2026-06-12", 30.minutes)
        val scanned = scanner.scan(tmp.root)
        assertEquals(3, scanned.size)
        val first = scanned[0]
        assertEquals("J-WAVE", first.stationName)
        assertEquals("LOGISTEED RADIONOMICS", first.programName)
        assertEquals("LOGISTEED RADIONOMICS 2026-06-12", first.title)
        assertEquals(LocalDate(2026, 6, 12).atStartOfDayIn(AiredAt.ZONE), first.airedAt)
        assertEquals(30.minutes, first.runtime)
        assertEquals("m4a", first.container)
        assertEquals(10, first.sizeBytes)
        val part = scanned[1]
        assertEquals("LOGISTEED RADIONOMICS 2026-06-12 (1)", part.title)
        assertEquals(first.airedAt, part.airedAt, "no tag: falls back to the date in the file name")
        assertEquals(Duration.ZERO, part.runtime)
        val mp3 = scanned[2]
        assertEquals("LFR", mp3.stationName)
        assertEquals("mp3", mp3.container)
        assertEquals(Instant.fromEpochMilliseconds(1_700_000_000_000), mp3.airedAt, "no tag, no date in name: mtime")
    }
    @Test
    fun emptyRootYieldsNothing() {
        assertEquals(emptyList(), scanner.scan(tmp.root))
        assertEquals(emptyList(), scanner.scan(File(tmp.root, "missing")))
    }
}
