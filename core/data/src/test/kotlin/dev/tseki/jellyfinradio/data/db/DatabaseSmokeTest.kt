package dev.tseki.jellyfinradio.data.db
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue
@RunWith(AndroidJUnit4::class)
class DatabaseSmokeTest {
    @Test
    fun opensInMemoryDatabase() {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JellyfinRadioDatabase::class.java,
        ).build()
        assertTrue(db.openHelper.writableDatabase.isOpen)
        db.close()
    }
}
