package dev.tseki.kikidame.ui.programs

import androidx.lifecycle.ViewModel
import dev.tseki.kikidame.ui.UiText
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.kikidame.domain.LibraryRepository
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ProgramSummary
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.sync.LibraryRefresher
import dev.tseki.kikidame.ui.programs.ProgramFilter.Publisher
import dev.tseki.kikidame.ui.programs.ProgramFilter.PublisherKey
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
    /** 配信元の絞り込み（#45）の選択。null は「すべて」。検索とは独立で、検索を閉じても残る。 */
    private val _publisher = MutableStateFlow<PublisherKey?>(null)
    /**
     * 絞り込みの入力（検索語・配信元）と手元の番組から導いた、画面が出すもの一式。
     * 一覧・配信元の候補・絞り込み中かどうかを別々の Flow にすると一瞬食い違うことがあるので 1 つにまとめる。
     */
    data class Filtered(
        /** 絞った後の一覧。 */
        val programs: List<ProgramSummary>,
        /** 配信元を選ぶシートに出す候補（手元の番組から集めたもの。番組数は検索語で絞らない）。空なら番組が 1 つも無い。 */
        val publishers: List<Publisher>,
        /** 選択中の配信元。候補に無い配信元、絞り込み自体を出さない（配信元が 2 種類未満）ときは選ばれていない扱い。 */
        val publisher: PublisherKey?,
        /** 絞り込みが効いているか。効いていれば画面は「よく聴く」「その他」の節を解除する。 */
        val isFiltering: Boolean,
    )
    /** null は読み込み前。 */
    val filtered: StateFlow<Filtered?> = combine(library.observePrograms(), _query, _publisher) { list, q, selected ->
        val publishers = ProgramFilter.publishers(list)
        val publisher = selected?.takeIf { key -> publishers.size >= 2 && publishers.any { it.key == key } }
        if (selected != null && publisher == null) {
            // 同期で選択中の配信元の番組が消えた（か、他の配信元が消えて絞り込み自体を出さなくなった）ら「すべて」に戻す。
            // 絞り込みだけ残って解除できない状態にしない。この計算の元になった選択と同じときだけ戻す（その間の操作は潰さない）
            _publisher.compareAndSet(selected, null)
        }
        Filtered(ProgramFilter.apply(list, q, publisher), publishers, publisher, ProgramFilter.isActive(q, publisher))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** サーバに接続済み（ライブラリ選択済み）なら「引っ張って更新」ができる。 */
    val canRefresh: StateFlow<Boolean> = sessionRepository.state
        .map { it is SessionState.Ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isRefreshing: StateFlow<Boolean> = refresher.isRefreshing
    /** 裏の同期（定期・起動時）のうち、手動が合流していない間だけ true。トップバーの下に細いバーを出す（#138）。 */
    val isSyncingInBackground: StateFlow<Boolean> = refresher.isSyncingInBackground
    /** 更新の結果と、ミニプレイヤーが消えた理由をスナックバーへ。 */
    val messages: Flow<UiText> = merge(refresher.messages, nowPlaying.messages)

    /** 絞り込みシートの検索欄。空にすれば検索の絞り込みは解除される（選択中のチップの × も同じ）。 */
    fun setQuery(query: String) {
        _query.value = query
    }
    /** 絞り込みシートで配信元を選ぶ。null は「すべて」（選択中のチップの × も同じ）。 */
    fun selectPublisher(publisher: PublisherKey?) {
        _publisher.value = publisher
    }
    /** 戻るボタンで絞り込みをまとめて解除する（検索語と配信元の両方）。 */
    fun clearFilters() {
        _query.value = ""
        _publisher.value = null
    }
    fun refresh() {
        viewModelScope.launch { refresher.refresh() }
    }
    /** よく聴くの印を付ける／外す（表示にだけ効く）。 */
    fun setStarred(programId: ProgramId, starred: Boolean) {
        viewModelScope.launch { library.setStarred(programId, starred) }
    }
}
