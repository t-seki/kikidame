package dev.tseki.kikidame.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.ServerItemId
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.sync.LibraryRefresher
import dev.tseki.kikidame.sync.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryPickUiState(
    val libraries: List<LibraryView>? = null,
    val selectedId: ServerItemId? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val musicCount: Int get() = libraries.orEmpty().count { it.isMusic }
    val canConfirm: Boolean get() = !isSaving && selectedId != null
}

@HiltViewModel
class LibraryPickViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val refresher: LibraryRefresher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryPickUiState())
    val uiState: StateFlow<LibraryPickUiState> = _uiState

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val libraries = sessionRepository.listLibraries()
                val current = (sessionRepository.state.first() as? SessionState.Ready)?.library?.id
                val music = libraries.filter { it.isMusic }
                // 音楽ライブラリが 1 つならそれを選択済みにする。画面自体は常に出す
                val preselected = current?.takeIf { id -> music.any { it.id == id } }
                    ?: music.singleOrNull()?.id
                _uiState.update { it.copy(libraries = libraries, selectedId = preselected, isLoading = false) }
            } catch (e: ServerException.Unauthorized) {
                // 401 はログアウトと同じ扱い。セッション状態が変わり、NavHost が接続画面へ導く
                _uiState.update { it.copy(isLoading = false, error = e.toUserMessage()) }
                sessionRepository.signOut()
            } catch (e: ServerException) {
                _uiState.update { it.copy(isLoading = false, error = e.toUserMessage()) }
            }
        }
    }

    fun select(library: LibraryView) {
        if (!library.isMusic) return
        _uiState.update { it.copy(selectedId = library.id) }
    }

    /** 決定。保存後に初回取得を走らせる（結果は [LibraryRefresher] の文言として一覧側に出る）。 */
    fun confirm(onDone: () -> Unit) {
        val s = _uiState.value
        val library = s.libraries?.firstOrNull { it.id == s.selectedId } ?: return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            sessionRepository.selectLibrary(library)
            _uiState.update { it.copy(isSaving = false) }
            onDone()
            refresher.launchRefresh()
        }
    }
}
