package dev.tseki.jellyfinradio.ui.programs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.jellyfinradio.domain.ImportResult
import dev.tseki.jellyfinradio.domain.LibraryRepository
import dev.tseki.jellyfinradio.domain.ProgramSummary
import dev.tseki.jellyfinradio.seed.SeedLocalLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
@HiltViewModel
class ProgramListViewModel @Inject constructor(
    library: LibraryRepository,
    private val seed: SeedLocalLibrary,
) : ViewModel() {
    val programs: StateFlow<List<ProgramSummary>?> = library.observePrograms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _seedMessage = MutableStateFlow<String?>(null)
    val seedMessage: StateFlow<String?> = _seedMessage
    val seedRoot: String? get() = seed.root?.absolutePath
    fun runSeed() {
        viewModelScope.launch {
            val result: ImportResult = seed.run()
            _seedMessage.value =
                "番組 ${result.addedPrograms} / 各回 ${result.addedEpisodes} を追加（既存 ${result.skippedEpisodes} はスキップ）"
        }
    }
    fun consumeSeedMessage() {
        _seedMessage.value = null
    }
}
