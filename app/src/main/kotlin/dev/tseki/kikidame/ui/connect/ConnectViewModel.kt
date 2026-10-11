package dev.tseki.kikidame.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.kikidame.R
import dev.tseki.kikidame.ui.UiText
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.sync.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectUiState(
    val serverUrl: String = "",
    val userName: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: UiText? = null,
) {
    val canSubmit: Boolean get() = !isSubmitting && serverUrl.isNotBlank() && userName.isNotBlank()
}

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val localNetworkPermission: LocalNetworkPermissionCheck,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ConnectUiState())
    val uiState: StateFlow<ConnectUiState> = _uiState

    init {
        viewModelScope.launch {
            val state = sessionRepository.state.first()
            if (state is SessionState.SignedOut) {
                _uiState.update {
                    it.copy(serverUrl = state.lastServerUrl ?: "", userName = state.lastUserName ?: "")
                }
            }
        }
    }

    fun onServerUrlChange(value: String) = _uiState.update { it.copy(serverUrl = value, error = null) }
    fun onUserNameChange(value: String) = _uiState.update { it.copy(userName = value, error = null) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }

    fun submit() {
        val s = _uiState.value
        if (!s.canSubmit) return
        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            try {
                sessionRepository.signIn(s.serverUrl, s.userName, s.password)
                _uiState.update { it.copy(isSubmitting = false, password = "") }
            } catch (e: ServerException) {
                _uiState.update { it.copy(isSubmitting = false, error = e.connectErrorText(e.toUserMessage(), localNetworkPermission)) }
            } catch (e: IllegalArgumentException) {
                _uiState.update { it.copy(isSubmitting = false, error = UiText.Res(R.string.connect_error_https_only)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // DataStore / Keystore の失敗。落とさずに画面へ
                _uiState.update { it.copy(isSubmitting = false, error = UiText.Res(R.string.connect_error_save_failed, e::class.simpleName.orEmpty())) }
            }
        }
    }
}
