package dev.tseki.kikidame.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 過去のスキーマ（`schemas/` の JSON）から最新まで AutoMigration で上がること。v4→v5・v5→v6 は既存の行の既定値まで見る。 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), KikidameDatabase::class.java)

    @Test
    fun v4ToV5AddsStarredWithFalseDefault() {
        helper.createDatabase(DB_NAME, 4).use { db ->
            db.execSQL(
                "INSERT INTO programs (id, serverItemId, name, stationName, syncEnabled, keepLatest, deleteAfterPlayed, goneSince) " +
                    "VALUES (1, 'srv-1', 'ハライチのターン！', 'TBSラジオ', 1, 3, 0, NULL)",
            )
        }
        helper.runMigrationsAndValidate(DB_NAME, 5, true).close()

        val migrated = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KikidameDatabase::class.java,
            DB_NAME,
        ).allowMainThreadQueries().build()
        try {
            val program = runBlocking { migrated.programDao().findById(1) }!!
            assertFalse(program.starred)
            assertEquals("ハライチのターン！", program.name)
            assertEquals(true, program.syncEnabled)
        } finally {
            migrated.close()
        }
    }

    /** v5→v6: episodes.performers（#70）。既存の行は出演者なし（空リスト）で読める。 */
    @Test
    fun v5ToV6AddsPerformersWithEmptyDefault() {
        helper.createDatabase(DB_NAME, 5).use { db ->
            db.execSQL(
                "INSERT INTO programs (id, serverItemId, name, stationName, syncEnabled, keepLatest, deleteAfterPlayed, goneSince, starred) " +
                    "VALUES (1, 'srv-1', 'ハライチのターン！', 'TBSラジオ', 0, NULL, 0, NULL, 0)",
            )
            db.execSQL(
                "INSERT INTO episodes (id, serverItemId, programId, title, airedAt, addedAt, runtimeTicks, sizeBytes, container) " +
                    "VALUES (10, 'ep-1', 1, '2026-09-18', 1789300800000, NULL, 36000000000, 0, 'm4a')",
            )
        }
        helper.runMigrationsAndValidate(DB_NAME, 6, true).close()

        val migrated = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KikidameDatabase::class.java,
            DB_NAME,
        ).allowMainThreadQueries().build()
        try {
            val episode = runBlocking { migrated.episodeDao().findById(10) }!!.episode
            assertEquals(emptyList(), episode.performers)
            assertEquals("2026-09-18", episode.title)
        } finally {
            migrated.close()
        }
    }

    /** 最初のスキーマからの通し。列追加が nullable か既定値付きで、途中のどこも手書きマイグレーションを要さないこと。 */
    @Test
    fun v1MigratesAllTheWayToLatest() {
        helper.createDatabase(DB_NAME, 1).close()
        helper.runMigrationsAndValidate(DB_NAME, 6, true).close()
    }

    companion object {
        private const val DB_NAME = "migration-test"
    }
}
