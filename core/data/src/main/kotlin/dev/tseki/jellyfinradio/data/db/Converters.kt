package dev.tseki.jellyfinradio.data.db
import androidx.room.TypeConverter
import dev.tseki.jellyfinradio.domain.DownloadState
import kotlin.time.Instant
class Converters {
    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilliseconds()
    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::fromEpochMilliseconds)
    @TypeConverter
    fun downloadStateToString(value: DownloadState): String = value.name
    @TypeConverter
    fun stringToDownloadState(value: String): DownloadState = DownloadState.valueOf(value)
}
