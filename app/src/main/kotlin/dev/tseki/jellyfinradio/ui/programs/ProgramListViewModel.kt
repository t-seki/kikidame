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
import dev.tseki.jellyfinradio.ui.programs.ProgramFilter.Station
import dev.tseki.jellyfinradio.ui.programs.ProgramFilter.StationKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
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
    /** 放送局のチップ（#45）の選択。null は「すべて」。検索とは独立で、検索を閉じても残る。 */
    private val _station = MutableStateFlow<StationKey?>(null)
    /**
     * 絞り込みの入力（検索語・局）と手元の番組から導いた、画面が出すもの一式。
     * 一覧・チップ・絞り込み中かどうかを別々の Flow にすると一瞬食い違うことがあるので 1 つにまとめる。
     */
    data class Filtered(
        /** 絞った後の一覧。 */
        val programs: List<ProgramSummary>,
        /** チップに出す局（手元の番組から集めたもの）。空なら番組が 1 つも無い。 */
        val stations: List<Station>,
        /** 選択中の局。チップに無い局は選ばれていない扱い。 */
        val station: StationKey?,
        /** 絞り込みが効いているか。効いていれば画面は「よく聴く」「その他」の節を解除する。 */
        val isFiltering: Boolean,
    )
    /** null は読み込み前。 */
    val filtered: StateFlow<Filtered?> = combine(library.observePrograms(), _query, _station) { list, q, selected ->
        val stations = ProgramFilter.stations(list)
        val station = selected?.takeIf { key -> stations.any { it.key == key } }
        Filtered(ProgramFilter.apply(list, q, station), stations, station, ProgramFilter.isActive(q, station))
    }
        // 同期で選択中の局の番組が全部消えたら「すべて」に戻す（局だけの絞り込みで 0 件のまま固まらない）
        .onEach { if (it.station == null && _station.value != null) _station.value = null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
    /** チップをタップ。選択中の局をもう一度タップしたら解除（「すべて」）。 */
    fun toggleStation(station: StationKey) {
        _station.value = if (_station.value == station) null else station
    }
    fun clearStation() {
        _station.value = null
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
