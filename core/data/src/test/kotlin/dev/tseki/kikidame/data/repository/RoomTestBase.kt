package dev.tseki.kikidame.data.repository
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tseki.kikidame.data.db.KikidameDatabase
import dev.tseki.kikidame.data.db.EpisodeEntity
import dev.tseki.kikidame.data.db.LocalFileEntity
import dev.tseki.kikidame.data.db.ProgramEntity
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.Ticks
import org.junit.After
import org.junit.Before
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
/** テストごとに新しい in-memory DB を開く。時計は固定して `updatedAt` を検証可能にする。 */
abstract class RoomTestBase {
    protected lateinit var db: KikidameDatabase
    protected var now: Instant = Instant.parse("2026-09-16T00:00:00Z")
    protected val clock: Clock = object : Clock {
        override fun now(): Instant = this@RoomTestBase.now
    }
    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KikidameDatabase::class.java,
        ).allowMainThreadQueries().build()
    }
    @After
    fun closeDatabase() {
        db.close()
    }
    /** サーバ ID を持たない手元の行（ADR 0001。かつてのシードが作っていた形）をテスト用に組み立てる。 */
    data class LocalRow(
        val publisher: String,
        val program: String,
        val title: String,
        val publishedAt: Instant,
        val runtime: Duration,
        val path: String,
    )

    protected fun scanned(
        publisher: String = "J-WAVE",
        program: String = "LOGISTEED RADIONOMICS",
        title: String,
        publishedAt: String,
        runtime: Duration = 30.minutes,
    ) = LocalRow(
        publisher = publisher,
        program = program,
        title = title,
        publishedAt = Instant.parse(publishedAt),
        runtime = runtime,
        path = "/sdcard/Android/data/dev.tseki.kikidame/files/episodes/$publisher/$program/$title.m4a",
    )

    /** 番組は (配信元, 番組名) で探して無ければ作り、各回は固定された DONE のファイル付きで入れる。 */
    protected suspend fun seed(rows: List<LocalRow>) {
        for (r in rows) {
            val programId = db.programDao().findByPublisherAndName(r.publisher, r.program)?.id
                ?: db.programDao().insert(ProgramEntity(serverItemId = null, name = r.program, publisherName = r.publisher))
            val episodeId = db.episodeDao().insert(
                EpisodeEntity(
                    serverItemId = null,
                    programId = programId,
                    title = r.title,
                    publishedAt = r.publishedAt,
                    addedAt = null,
                    runtimeTicks = Ticks.fromDuration(r.runtime),
                    sizeBytes = 1024,
                    container = "m4a",
                ),
            )
            db.localFileDao().upsert(LocalFileEntity(episodeId, DownloadState.DONE, r.path, pinned = true, downloadedAt = now))
        }
    }
}
