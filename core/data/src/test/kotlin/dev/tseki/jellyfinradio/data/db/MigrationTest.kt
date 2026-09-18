package dev.tseki.jellyfinradio.data.db

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

/** 過去のスキーマ（`schemas/` の JSON）から最新まで AutoMigration で上がること。v4→v5 は既存の行の既定値まで見る。 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), JellyfinRadioDatabase::class.java)

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
            JellyfinRadioDatabase::class.java,
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

    /** 最初のスキーマからの通し。列追加が nullable か既定値付きで、途中のどこも手書きマイグレーションを要さないこと。 */
    @Test
    fun v1MigratesAllTheWayToLatest() {
        helper.createDatabase(DB_NAME, 1).close()
        helper.runMigrationsAndValidate(DB_NAME, 5, true).close()
    }

    companion object {
        private const val DB_NAME = "migration-test"
    }
}
