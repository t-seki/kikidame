package dev.tseki.jellyfinradio.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.jellyfinradio.playback.NowPlaying
import dev.tseki.jellyfinradio.playback.NowPlayingState
import dev.tseki.jellyfinradio.playback.PlayerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 一覧の下のミニプレイヤー。読みはプロセス内の [NowPlaying]（サービスを起動しない）、
 * 操作だけ [PlayerConnection] を使う（ADR 0006）。
 */
@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    nowPlaying: NowPlaying,
    private val connection: PlayerConnection,
) : ViewModel() {
    val state: StateFlow<NowPlayingState?> = nowPlaying.state

    /** ミニプレイヤーが見えている = サービスが生きているので、ここで controller を取っても新たな起動にはならない。 */
    fun togglePlayPause() {
        viewModelScope.launch {
            try {
                val controller = connection.controller()
                if (controller.isPlaying) controller.pause() else controller.play()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "toggle failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "MiniPlayerViewModel"
    }
}
