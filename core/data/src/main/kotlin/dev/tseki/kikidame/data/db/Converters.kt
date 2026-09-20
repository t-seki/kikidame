package dev.tseki.kikidame.data.db
import androidx.room.TypeConverter
import dev.tseki.kikidame.domain.DownloadState
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
    /** 出演者の一覧を 1 列に。名前に現れない改行で区切る（Jellyfin は `/` `;` で既に分けている）。空は空文字。 */
    @TypeConverter
    fun stringListToString(value: List<String>): String = value.joinToString(LIST_SEPARATOR)
    @TypeConverter
    fun stringToStringList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(LIST_SEPARATOR)
    private companion object {
        const val LIST_SEPARATOR = "\n"
    }
}
