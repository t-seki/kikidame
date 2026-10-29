package dev.tseki.kikidame.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.kikidame.domain.LibraryRepository
import dev.tseki.kikidame.domain.ProgramImage
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.playback.NowPlayingState
import dev.tseki.kikidame.playback.PlayerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 一覧の下のミニプレイヤー。読みはプロセス内の [NowPlaying]（サービスを起動しない）、
 * 操作だけ [PlayerConnection] を使う（ADR 0004）。
 * 番組の画像は [LibraryRepository] から引く（回の ID は [NowPlaying] から取り、サービスは起動しない。ADR 0004 に反しない）。
 */
@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    nowPlaying: NowPlaying,
    library: LibraryRepository,
    private val connection: PlayerConnection,
) : ViewModel() {
    val state: StateFlow<NowPlayingState?> = nowPlaying.state

    /**
     * 聴いている回の番組の画像（ADR 0009）。無ければ null。[state] は再生位置で毎秒変わるので、回の ID が変わったときだけ引き直す。
     * 番組を購読するので、同期が画像を取り直しても追従する。回の画像は使わない。
     */
    val programImage: StateFlow<ProgramImage?> = nowPlaying.state
        .map { it?.episodeId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) return@flatMapLatest flowOf(null)
            flow<ProgramImage?> {
                val programId = library.getEpisode(id)?.episode?.programId ?: return@flow emit(null)
                emitAll(library.observeProgram(programId).map { it?.image })
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
