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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    /**
     * 検索（#44）。検索語と検索欄の開閉は ViewModel に持つので、画面回転でも、番組を開いて戻っても残る
     * （番組一覧はバックスタックの根で、この ViewModel は生き続ける）。プロセス死からは復元しない。
     */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()
    /** 絞り込みが効いているか。効いていれば画面は「よく聴く」「その他」の節を解除する（#45 の局チップもこの条件に加わる）。 */
    val isFiltering: StateFlow<Boolean> = _query.map { ProgramFilter.isActive(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    /** 検索で絞った後の一覧。null は読み込み前。 */
    val programs: StateFlow<List<ProgramSummary>?> = combine(library.observePrograms(), _query) { list, q ->
        ProgramFilter.apply(list, q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** サーバに接続済み（ライブラリ選択済み）なら「引っ張って更新」ができる。 */
    val canRefresh: StateFlow<Boolean> = sessionRepository.state
        .map { it is SessionState.Ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isRefreshing: StateFlow<Boolean> = refresher.isRefreshing
    /** 更新の結果と、ミニプレイヤーが消えた理由をスナックバーへ。 */
    val messages: Flow<String> = merge(refresher.messages, nowPlaying.messages)

    fun setQuery(query: String) {
        _query.value = query
    }
    /** 虫眼鏡で開く。 */
    fun startSearch() {
        _isSearching.value = true
    }
    /** ← か戻るボタンで閉じる。検索語も消すので絞り込みは解除される。 */
    fun stopSearch() {
        _isSearching.value = false
        _query.value = ""
    }
    fun refresh() {
        viewModelScope.launch { refresher.refresh() }
    }
    /** よく聴くの印を付ける／外す（表示にだけ効く）。 */
    fun setStarred(programId: ProgramId, starred: Boolean) {
        viewModelScope.launch { library.setStarred(programId, starred) }
    }
}
