package dev.tseki.jellyfinradio.ui.episodes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.EpisodeWithState
import dev.tseki.jellyfinradio.domain.LibraryRepository
import dev.tseki.jellyfinradio.domain.ProgramId
import dev.tseki.jellyfinradio.ui.EpisodeDetailsRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
/** 各回の詳細画面（#43）。番組の各回一覧から該当の回を引き、ダウンロードや再生で変わればそのまま追従する。 */
@HiltViewModel
class EpisodeDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    library: LibraryRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<EpisodeDetailsRoute>()
    private val episodeId = EpisodeId(route.episodeId)
    /** null は読み込み前か、その回が消えた後。 */
    val item: StateFlow<EpisodeWithState?> = library.observeEpisodes(ProgramId(route.programId))
        .map { list -> list.firstOrNull { it.episode.id == episodeId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
