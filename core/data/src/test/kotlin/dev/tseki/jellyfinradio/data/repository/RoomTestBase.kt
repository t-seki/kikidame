package dev.tseki.jellyfinradio.data.repository
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tseki.jellyfinradio.data.db.JellyfinRadioDatabase
import dev.tseki.jellyfinradio.domain.ScannedEpisode
import org.junit.After
import org.junit.Before
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
/** テストごとに新しい in-memory DB を開く。時計は固定して `updatedAt` を検証可能にする。 */
abstract class RoomTestBase {
    protected lateinit var db: JellyfinRadioDatabase
    protected val now: Instant = Instant.parse("2026-09-16T00:00:00Z")
    protected val clock: Clock = object : Clock {
        override fun now(): Instant = this@RoomTestBase.now
    }
    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JellyfinRadioDatabase::class.java,
        ).allowMainThreadQueries().build()
    }
    @After
    fun closeDatabase() {
        db.close()
    }
    protected fun scanned(
        station: String = "J-WAVE",
        program: String = "LOGISTEED RADIONOMICS",
        title: String,
        airedAt: String,
        runtime: Duration = 30.minutes,
    ) = ScannedEpisode(
        stationName = station,
        programName = program,
        title = title,
        path = "/sdcard/Android/data/dev.tseki.jellyfinradio/files/episodes/$station/$program/$title.m4a",
        airedAt = Instant.parse(airedAt),
        runtime = runtime,
        sizeBytes = 1024,
        container = "m4a",
    )
}
