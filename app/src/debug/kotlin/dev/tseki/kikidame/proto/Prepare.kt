package dev.tseki.kikidame.proto

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.tseki.kikidame.domain.LibraryRepository
import dev.tseki.kikidame.playback.EpisodeMediaItems
import dev.tseki.kikidame.playback.PlaybackService
import kotlinx.coroutines.guava.await

/**
 * 試作 #204 の追加: 再生せずに「準備」だけする。
 *
 * FGS は始めない。[MediaController] で PlaybackService に bind するだけ（bind でサービスが作られ、セッションができる）。
 * 最後に聴いていた回の mediaId だけを入れた 1 件を `setMediaItems`（開始位置は未指定）で送ると、サービスの
 * `onSetMediaItems` が番組のキューと保存位置を組む（Auto で各回を選んだときと同じ道。`onPlaybackResumption` と同じ
 * `programQueueStartingAt`）。そのあと `prepare()` だけして `play()` はしない（playWhenReady = false）。
 * controller は離さず持ち続ける（離すと unbind でサービスが消えうるため）。
 */
object Prepare {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun libraryRepository(): LibraryRepository
    }

    // 試作なので持ちっぱなし。メインスレッドからだけ触る
    private var controller: MediaController? = null

    suspend fun run(context: Context) {
        val app = context.applicationContext
        val repo = EntryPointAccessors.fromApplication(app, Deps::class.java).libraryRepository()
        val recent = repo.getRecentlyListened(1).firstOrNull()
        if (recent == null) {
            Log.w(Proto.TAG, "prepare: no recently listened episode")
            return
        }
        Log.i(Proto.TAG, "prepare: connecting MediaController (no FGS), episode=${recent.episode.id}")
        controller?.release()
        val c = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java)))
            .buildAsync()
            .await()
        controller = c
        c.setMediaItems(
            listOf(MediaItem.Builder().setMediaId(EpisodeMediaItems.mediaId(recent.episode.id)).build()),
            C.INDEX_UNSET,
            C.TIME_UNSET,
        )
        c.playWhenReady = false
        c.prepare()
        Log.i(Proto.TAG, "prepare: done, playWhenReady=${c.playWhenReady} state=${c.playbackState}")
    }
}
