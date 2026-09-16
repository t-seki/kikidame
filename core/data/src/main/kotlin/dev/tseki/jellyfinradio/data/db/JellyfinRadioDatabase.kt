package dev.tseki.jellyfinradio.data.db
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
@Entity
data class PlaceholderEntity(@PrimaryKey val id: Long)
@Database(entities = [PlaceholderEntity::class], version = 1, exportSchema = true)
abstract class JellyfinRadioDatabase : RoomDatabase()
