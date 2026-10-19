package dev.tseki.kikidame.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.kikidame.domain.LibraryRepository
import dev.tseki.kikidame.domain.LocalEpisode
import dev.tseki.kikidame.domain.LocalStorageRepository
import dev.tseki.kikidame.domain.LocalStorageUsage
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ProgramStorage
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.sync.LibraryRefresher
import dev.tseki.kikidame.ui.StorageProgramRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 「すべての回」の絞り込み（#335）。両方を選べば両方に当たる回（AND）だけ。見え方だけを変え、何も消さない。 */
data class StorageFilter(val pinned: Boolean = false, val played: Boolean = false) {
    fun matches(episode: LocalEpisode): Boolean = (!pinned || episode.pinned) && (!played || episode.played)
}

/** 内訳の画面（#335）: 上に合計、「番組ごと」と「すべての回」のタブ。「すべての回」で選んだ回を消す。 */
@HiltViewModel
class StorageViewModel @Inject constructor(
    storage: LocalStorageRepository,
    library: LibraryRepository,
    nowPlaying: NowPlaying,
    refresher: LibraryRefresher,
) : StorageSelectionViewModel(storage, nowPlaying, refresher) {
    /** 設定の「手元のファイル」の行と同じ合計。null は読み込み前。 */
    val usage: StateFlow<LocalStorageUsage?> = library.observeLocalStorage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 手元の回のある番組（容量の大きい順）。null は読み込み前。 */
    val programs: StateFlow<List<ProgramStorage>?> = storage.observePrograms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val allEpisodes = storage.observeEpisodes()

    private val _filter = MutableStateFlow(StorageFilter())
    val filter: StateFlow<StorageFilter> = _filter

    fun setFilter(value: StorageFilter) {
        _filter.value = value
    }

    override val visibleEpisodes: StateFlow<List<LocalEpisode>?> = combine(allEpisodes, _filter) { list, f -> list.filter(f::matches) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        keepOnly(allEpisodes)
    }
}

/** 番組ごとの手元の回の一覧（#335）。公開日の新しい順。手元の回が 0 になったら内訳の画面に戻る。 */
@HiltViewModel
class StorageProgramViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    storage: LocalStorageRepository,
    nowPlaying: NowPlaying,
    refresher: LibraryRefresher,
) : StorageSelectionViewModel(storage, nowPlaying, refresher) {
    private val route = savedStateHandle.toRoute<StorageProgramRoute>()

    /** トップバーの番組名。取得元 ID の無い番組は最後の回を消すと番組ごと消えるので、行き先に持たせた名前を使う。 */
    val programName: String = route.programName

    private val episodes = storage.observeEpisodes(ProgramId(route.programId))

    override val visibleEpisodes: StateFlow<List<LocalEpisode>?> = episodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        keepOnly(episodes)
    }
}
