package dev.tseki.kikidame.playback
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.Program
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
                    .setAlbumTitle(program?.publisherName)
                    .setDurationMs(item.episode.runtime.inWholeMilliseconds)
                    .build(),
            )
            .build()
    }

    /** metadata の尺。0 以下は「分からない」として null。 */
    fun runtimeOf(mediaItem: MediaItem?): Duration? =
        mediaItem?.mediaMetadata?.durationMs?.takeIf { it > 0 }?.milliseconds
}

/**
 * 尺が分かるときの尺。[Player.getDuration] が [C.TIME_UNSET] でも 0 以下でもなければそれを、
 * そうでなければ現在の項目の metadata の尺（[EpisodeMediaItems.runtimeOf]）。どちらも無ければ null。
 */
fun Player.knownRuntime(): Duration? =
    duration.takeIf { it != C.TIME_UNSET && it > 0 }?.milliseconds
        ?: EpisodeMediaItems.runtimeOf(currentMediaItem)
