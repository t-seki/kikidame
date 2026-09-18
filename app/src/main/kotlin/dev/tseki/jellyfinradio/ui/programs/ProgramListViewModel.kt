package dev.tseki.jellyfinradio.ui.programs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.jellyfinradio.domain.LibraryRepository
import dev.tseki.jellyfinradio.domain.ProgramId
import dev.tseki.jellyfinradio.domain.ProgramSummary
import dev.tseki.jellyfinradio.domain.SessionRepository
import dev.tseki.jellyfinradio.domain.SessionState
import dev.tseki.jellyfinradio.playback.NowPlaying
import dev.tseki.jellyfinradio.sync.LibraryRefresher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProgramListViewModel @Inject constructor(
    private val library: LibraryRepository,
    sessionRepository: SessionRepository,
    private val refresher: LibraryRefresher,
    nowPlaying: NowPlaying,
) : ViewModel() {
    val programs: StateFlow<List<ProgramSummary>?> = library.observePrograms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** サーバに接続済み（ライブラリ選択済み）なら「引っ張って更新」ができる。 */
    val canRefresh: StateFlow<Boolean> = sessionRepository.state
        .map { it is SessionState.Ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isRefreshing: StateFlow<Boolean> = refresher.isRefreshing
    /** 更新の結果と、ミニプレイヤーが消えた理由をスナックバーへ。 */
    val messages: Flow<String> = merge(refresher.messages, nowPlaying.messages)

    fun refresh() {
        viewModelScope.launch { refresher.refresh() }
    }
    /** よく聴くの印を付ける／外す（表示にだけ効く）。 */
    fun setStarred(programId: ProgramId, starred: Boolean) {
        viewModelScope.launch { library.setStarred(programId, starred) }
    }
}
