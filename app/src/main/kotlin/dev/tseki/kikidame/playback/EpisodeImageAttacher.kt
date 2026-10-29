package dev.tseki.kikidame.playback
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import dev.tseki.kikidame.domain.EpisodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
/**
 * 聴く回が替わったら、その回の画像（[load]）を現在の [MediaItem] の `artworkData` に載せる。
 * キューは番組の手元の回を全部積む（[ProgramQueue]）ので、全件の画像は読まず、再生に入った回の分だけ読む。
 * 載せるのは同じ回の項目の差し替え（`replaceMediaItem`）で、URI は変えない。画像が無い（[load] が null）ときは何もしない。
 * 通知・ロック画面・Auto・再生画面はこの `artworkData` を読む。
 */
class EpisodeImageAttacher(
    private val player: Player,
    private val scope: CoroutineScope,
    private val load: suspend (EpisodeId) -> ByteArray?,
) : Player.Listener {
    private var job: Job? = null

    fun attach() = player.addListener(this)

    fun detach() {
        player.removeListener(this)
        job?.cancel()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        job?.cancel()
        val episodeId = EpisodeMediaItems.episodeId(mediaItem) ?: return
        if (mediaItem?.mediaMetadata?.artworkData != null) return
        job = scope.launch {
            val bytes = load(episodeId) ?: return@launch
            // 読んでいる間に別の回へ移っていたら、または既に載っていたら何もしない
            val current = player.currentMediaItem ?: return@launch
            if (EpisodeMediaItems.episodeId(current) != episodeId || current.mediaMetadata.artworkData != null) return@launch
            val withArtwork = current.buildUpon()
                .setMediaMetadata(
                    current.mediaMetadata.buildUpon()
                        .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build(),
                )
                .build()
            player.replaceMediaItem(player.currentMediaItemIndex, withArtwork)
        }
    }
}
