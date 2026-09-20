package dev.tseki.kikidame.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.playback.NowPlayingState
import dev.tseki.kikidame.playback.PlayerConnection
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

    /**
     * ミニプレイヤーが見えている = サービスが生きているので、ここで controller を取っても新たな起動にはならない。
     * 操作のあいだだけ持ち、離す（一覧画面にいる間サービスに bind し続けない）。
     */
    fun togglePlayPause() {
        viewModelScope.launch {
            try {
                connection.use { controller ->
                    if (controller.isPlaying) controller.pause() else controller.play()
                }
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
