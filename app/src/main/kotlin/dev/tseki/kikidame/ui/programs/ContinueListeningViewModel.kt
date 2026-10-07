package dev.tseki.kikidame.ui.programs

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LibraryRepository
import dev.tseki.kikidame.domain.LocalDeletionScope
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.PlaybackStateRepository
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.playback.NowPlayingState
import dev.tseki.kikidame.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Clock

/**
 * 番組の画面の「続きから」タブ（#151）。途中まで聴いた各回を番組をまたいで最後に聴いた順に、件数の上限なしで出す
 * （条件は Android Auto の「続きから」と同じ [LibraryRepository.observeRecentlyListened]）。
 * 行の操作は各回一覧と同じ（右端で再生済みの切り替え、長押しで固定を外す・ファイルの削除・詳細）。
 * 再生済みにしたときだけ［元に戻す］を出すので、その合図を [markedPlayed] で画面へ渡す。
 *
 * 更新の結果と、ミニプレイヤーが消えた理由（[NowPlaying.messages]）は同じ画面の [ProgramListViewModel] が受け取る。
 * ここで重ねて受けると 1 つのメッセージを取り合う（Channel）か二重に出るので、この ViewModel は自分の操作の結果だけを出す。
 */
@HiltViewModel
class ContinueListeningViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val playbackStates: PlaybackStateRepository,
    private val clock: Clock,
    private val downloads: DownloadRepository,
    nowPlaying: NowPlaying,
) : ViewModel() {
    /** 「続きから」の 1 行。[program] は番組名と同期対象かどうか（シートの「固定を外す」と削除の文言）に使う。 */
    data class Item(val episode: EpisodeWithState, val program: Program?)

    /** null は読み込み前。再生位置の保存・再生済み・ファイルの削除・同期に追従する。 */
    val items: StateFlow<List<Item>?> = combine(library.observeRecentlyListened(), library.observePrograms()) { episodes, programs ->
        val byId = programs.associate { it.program.id to it.program }
        episodes.map { Item(it, byId[it.episode.programId]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 聴いている回。行の印と背景に使う。再生位置（1 秒ごとに変わる）は行に要らないので落とす（各回一覧と同じ）。 */
    val nowPlaying: StateFlow<NowPlayingState?> = nowPlaying.state
        .map { it?.copy(positionMs = 0, durationMs = 0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), nowPlaying.state.value?.copy(positionMs = 0, durationMs = 0))

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** この画面での操作の結果（固定を外した・ファイルを消した・失敗）。 */
    val messages: Flow<UiText> = _messages

    private val _markedPlayed = MutableSharedFlow<EpisodeId>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** 「続きから」で再生済みにした各回。画面は「再生済みにしました」と［元に戻す］を出す。 */
    val markedPlayed: Flow<EpisodeId> = _markedPlayed

    /**
     * 右端のアイコンの再生済みの切り替え。再生済みにした行は「続きから」から消える。
     * 再生済みにしたときは [markedPlayed] で知らせる。［元に戻す］は `played = false` でここを呼ぶ（記録の更新時刻が変わるので、戻した回は先頭に来る）。
     */
    fun setPlayed(episodeId: EpisodeId, played: Boolean) = act {
        playbackStates.update(episodeId) { PlaybackRules.setPlayed(it, played, clock.now()) }
        if (played) _markedPlayed.tryEmit(episodeId)
    }

    fun unpin(episodeId: EpisodeId) = act {
        downloads.unpin(episodeId)
        _messages.tryEmit(UiText.Res(R.string.episode_list_msg_unpinned))
    }

    /** 各回一覧の「ファイルを削除」と同じ。消した回は「続きから」から消える。 */
    fun deleteLocal(episodeId: EpisodeId) = act {
        val syncEnabled = items.value?.firstOrNull { it.episode.episode.id == episodeId }?.program?.syncEnabled == true
        _messages.tryEmit(
            when (downloads.deleteLocal(episodeId)) {
                LocalDeletionScope.FILE_ONLY ->
                    if (syncEnabled) UiText.Res(R.string.episode_list_msg_file_deleted_synced)
                    else UiText.Res(R.string.episode_list_msg_file_deleted)
                LocalDeletionScope.EPISODE -> UiText.Res(R.string.episode_list_msg_episode_removed)
            },
        )
    }

    /** Room / ファイル I/O の失敗で落とさず、文言にして出す（各回一覧と同じ）。 */
    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "continue listening action failed", e)
                _messages.tryEmit(UiText.Res(R.string.episode_list_msg_action_failed, e::class.simpleName.orEmpty()))
            }
        }
    }

    private companion object {
        const val TAG = "ContinueListeningVM"
    }
}
