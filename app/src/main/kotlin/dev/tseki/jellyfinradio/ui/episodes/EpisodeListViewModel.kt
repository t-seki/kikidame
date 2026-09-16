package dev.tseki.jellyfinradio.ui.episodes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.EpisodeWithState
import dev.tseki.jellyfinradio.domain.LibraryRepository
import dev.tseki.jellyfinradio.domain.PlaybackRules
import dev.tseki.jellyfinradio.domain.PlaybackStateRepository
import dev.tseki.jellyfinradio.domain.Program
import dev.tseki.jellyfinradio.domain.ProgramId
import dev.tseki.jellyfinradio.ui.EpisodeListRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Clock
@HiltViewModel
class EpisodeListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    library: LibraryRepository,
    private val playbackStates: PlaybackStateRepository,
    private val clock: Clock,
) : ViewModel() {
    private val programId = ProgramId(savedStateHandle.toRoute<EpisodeListRoute>().programId)
    val program: StateFlow<Program?> = library.observeProgram(programId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val episodes: StateFlow<List<EpisodeWithState>?> = library.observeEpisodes(programId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun setPlayed(episodeId: EpisodeId, played: Boolean) {
        viewModelScope.launch {
            playbackStates.update(episodeId) { PlaybackRules.setPlayed(it, played, clock.now()) }
        }
    }
}
