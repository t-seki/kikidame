package dev.tseki.kikidame.ui.connect

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.tseki.kikidame.LocalNetworkPermission
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.domain.SmbConnection
import dev.tseki.kikidame.sync.LibraryRefresher
import dev.tseki.kikidame.ui.UiText
import dev.tseki.kikidame.ui.source.SourceChanger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SmbConnectUiState(
    val host: String = "",
    val share: String = "",
    val path: String = "",
    val guest: Boolean = false,
    val userName: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: UiText? = null,
) {
    val canSubmit: Boolean
        get() = !isSubmitting && host.isNotBlank() && share.isNotBlank() && (guest || userName.isNotBlank())
}

/** SMB の共有に接続する画面（#198）。共有の直下が読めたら保存し、初回の全走査を始める。 */
@HiltViewModel
class SmbConnectViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionRepository: SessionRepository,
    private val sourceChanger: SourceChanger,
    private val refresher: LibraryRefresher,
) : ViewModel() {
    private companion object {
        const val TAG = "SmbConnect"
    }

    private val _uiState = MutableStateFlow(SmbConnectUiState())
    val uiState: StateFlow<SmbConnectUiState> = _uiState

    init {
        viewModelScope.launch {
            // ログアウトした後の入力補助（パスワードは残さない）
            val last = (sessionRepository.state.first() as? SessionState.SignedOut)?.lastSmb ?: return@launch
            _uiState.update {
                it.copy(host = last.host, share = last.share, path = last.path, userName = last.userName, guest = last.guest)
            }
        }
    }

    fun onHostChange(value: String) = _uiState.update { it.copy(host = value, error = null) }
    fun onShareChange(value: String) = _uiState.update { it.copy(share = value, error = null) }
    fun onPathChange(value: String) = _uiState.update { it.copy(path = value, error = null) }
    fun onGuestChange(value: Boolean) = _uiState.update { it.copy(guest = value, error = null) }
    fun onUserNameChange(value: String) = _uiState.update { it.copy(userName = value, error = null) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }

    /** 「取得元を変える」。手元を全部消した後に [onDone]（初回の取得元の選択画面へ戻る）を呼ぶ。 */
    fun changeSource(onDone: () -> Unit) {
        viewModelScope.launch {
            sourceChanger.changeSource()
            onDone()
        }
    }

    fun submit() {
        val s = _uiState.value
        if (!s.canSubmit) return
        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            try {
                sessionRepository.connectSharedFolder(
                    SmbConnection(
                        host = s.host,
                        share = s.share,
                        path = s.path,
                        userName = if (s.guest) "" else s.userName,
                        password = if (s.guest) "" else s.password,
                        guest = s.guest,
                    ),
                )
                _uiState.update { it.copy(isSubmitting = false, password = "") }
                // 画面が消えても最後まで走らせる（結果の文言は一覧側に出る）
                refresher.launchRefresh()
            } catch (e: ServerException) {
                Log.w(TAG, "connect to the shared folder failed", e)
                val hint = if (e is ServerException.Unreachable && LocalNetworkPermission.isRequiredAndMissing(context)) {
                    UiText.Res(R.string.connect_hint_local_network_permission)
                } else {
                    null
                }
                _uiState.update { it.copy(isSubmitting = false, error = UiText.Joined(listOfNotNull(e.toSmbUserMessage(), hint))) }
            } catch (e: IllegalArgumentException) {
                _uiState.update { it.copy(isSubmitting = false, error = UiText.Res(R.string.smb_connect_error_invalid)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // DataStore / Keystore の失敗。落とさずに画面へ
                _uiState.update { it.copy(isSubmitting = false, error = UiText.Res(R.string.connect_error_save_failed, e::class.simpleName.orEmpty())) }
            }
        }
    }
}

/** SMB の接続画面の文言（Jellyfin の [dev.tseki.kikidame.sync.toUserMessage] は URL やサーバの文言なので別にする）。 */
internal fun ServerException.toSmbUserMessage(): UiText = when (this) {
    is ServerException.Unauthorized -> UiText.Res(R.string.smb_error_unauthorized)
    is ServerException.Unreachable -> UiText.Res(R.string.smb_error_unreachable)
    is ServerException.Failed -> UiText.Res(R.string.smb_error_failed, message.orEmpty())
}
