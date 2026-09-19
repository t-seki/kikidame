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
     * 検索語（#44）。ViewModel に持つので、画面回転でも、番組を開いて戻っても残る
     * （番組一覧はバックスタックの根で、この ViewModel は生き続ける）。プロセス死からは復元しない。
     * 絞り込みシートの開閉は画面側の remember（他のシートと同じ）。
     */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    /** 放送局の絞り込み（#45）の選択。null は「すべて」。検索とは独立で、検索を閉じても残る。 */
    private val _station = MutableStateFlow<StationKey?>(null)
    /**
     * 絞り込みの入力（検索語・局）と手元の番組から導いた、画面が出すもの一式。
     * 一覧・局の候補・絞り込み中かどうかを別々の Flow にすると一瞬食い違うことがあるので 1 つにまとめる。
     */
    data class Filtered(
        /** 絞った後の一覧。 */
        val programs: List<ProgramSummary>,
        /** 局を選ぶシートに出す候補（手元の番組から集めたもの。番組数は検索語で絞らない）。空なら番組が 1 つも無い。 */
        val stations: List<Station>,
        /** 選択中の局。候補に無い局、絞り込み自体を出さない（局が 2 種類未満）ときは選ばれていない扱い。 */
        val station: StationKey?,
        /** 絞り込みが効いているか。効いていれば画面は「よく聴く」「その他」の節を解除する。 */
        val isFiltering: Boolean,
    )
    /** null は読み込み前。 */
    val filtered: StateFlow<Filtered?> = combine(library.observePrograms(), _query, _station) { list, q, selected ->
        val stations = ProgramFilter.stations(list)
        val station = selected?.takeIf { key -> stations.size >= 2 && stations.any { it.key == key } }
        if (selected != null && station == null) {
            // 同期で選択中の局の番組が消えた（か、他の局が消えて絞り込み自体を出さなくなった）ら「すべて」に戻す。
            // 絞り込みだけ残って解除できない状態にしない。この計算の元になった選択と同じときだけ戻す（その間の操作は潰さない）
            _station.compareAndSet(selected, null)
        }
        Filtered(ProgramFilter.apply(list, q, station), stations, station, ProgramFilter.isActive(q, station))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** サーバに接続済み（ライブラリ選択済み）なら「引っ張って更新」ができる。 */
    val canRefresh: StateFlow<Boolean> = sessionRepository.state
        .map { it is SessionState.Ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isRefreshing: StateFlow<Boolean> = refresher.isRefreshing
    /** 更新の結果と、ミニプレイヤーが消えた理由をスナックバーへ。 */
    val messages: Flow<String> = merge(refresher.messages, nowPlaying.messages)

    /** 絞り込みシートの検索欄。空にすれば検索の絞り込みは解除される（選択中のチップの × も同じ）。 */
    fun setQuery(query: String) {
        _query.value = query
    }
    /** 絞り込みシートで局を選ぶ。null は「すべて」（選択中のチップの × も同じ）。 */
    fun selectStation(station: StationKey?) {
        _station.value = station
    }
    /** 戻るボタンで絞り込みをまとめて解除する（検索語と局の両方）。 */
    fun clearFilters() {
        _query.value = ""
        _station.value = null
    }
    fun refresh() {
        viewModelScope.launch { refresher.refresh() }
    }
    /** よく聴くの印を付ける／外す（表示にだけ効く）。 */
    fun setStarred(programId: ProgramId, starred: Boolean) {
        viewModelScope.launch { library.setStarred(programId, starred) }
    }
}
