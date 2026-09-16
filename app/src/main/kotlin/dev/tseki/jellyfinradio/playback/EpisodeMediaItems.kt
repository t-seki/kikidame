package dev.tseki.jellyfinradio.playback
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.EpisodeWithState
import dev.tseki.jellyfinradio.domain.Program
import java.io.File
/** 各回 ↔ Media3 の [MediaItem]。`mediaId` はローカルの各回 ID。 */
object EpisodeMediaItems {
    fun mediaId(episodeId: EpisodeId): String = episodeId.value.toString()
    fun episodeId(mediaItem: MediaItem?): EpisodeId? =
        mediaItem?.mediaId?.toLongOrNull()?.let(::EpisodeId)
    /** 手元にファイルが無い各回は変換できない（呼び出し側で [EpisodeWithState.isPlayable] を確認する）。 */
    fun toMediaItem(item: EpisodeWithState, program: Program?): MediaItem {
        val path = requireNotNull(item.localFile?.path) { "episode ${item.episode.id} has no local file" }
        return MediaItem.Builder()
            .setMediaId(mediaId(item.episode.id))
            .setUri(File(path).toUri())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.episode.title)
                    .setArtist(program?.name)
                    .setAlbumTitle(program?.stationName)
                    .setDurationMs(item.episode.runtime.inWholeMilliseconds)
                    .build(),
            )
            .build()
    }
}
