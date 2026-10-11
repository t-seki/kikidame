package dev.tseki.kikidame.ui.connect

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.DeviceFolder
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.domain.UnsupportedFolderException
import dev.tseki.kikidame.sync.LibraryRefresher
import dev.tseki.kikidame.sync.SyncScheduler
import dev.tseki.kikidame.ui.UiText
import dev.tseki.kikidame.ui.source.SourceChanger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeviceFolderUiState(
    /** ログアウトしたフォルダの、端末の中の場所（[displayPath]。#199 の決定 5）。選び直しを案内する。 */
    val lostFolder: UiText? = null,
    val isSubmitting: Boolean = false,
    val error: UiText? = null,
)

/**
 * 端末のフォルダ（SAF、#199）を選ぶ画面。OS のフォルダ選択の結果を受け、端末のストレージのフォルダか確かめ（決定 3）、
 * 直下が読めたら保存して初回の全走査を始める（決定 8）。
 */
@HiltViewModel
class DeviceFolderViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val sourceChanger: SourceChanger,
    private val refresher: LibraryRefresher,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {
    private companion object {
        const val TAG = "DeviceFolder"
    }

    private val _uiState = MutableStateFlow(DeviceFolderUiState())
    val uiState: StateFlow<DeviceFolderUiState> = _uiState

    init {
        viewModelScope.launch {
            val lost = (sessionRepository.state.first() as? SessionState.SignedOut)?.lastDeviceFolder ?: return@launch
            _uiState.update { it.copy(lostFolder = lost.displayPath()) }
        }
    }

    /** 「取得元を変える」。手元を全部消した後に [onDone]（初回の取得元の選択画面へ戻る）を呼ぶ。 */
    fun changeSource(onDone: () -> Unit) {
        viewModelScope.launch {
            sourceChanger.changeSource()
            onDone()
        }
    }

    /** OS のフォルダ選択の結果。取り消されたら（[treeUri] が null）何もしない。 */
    fun onPicked(treeUri: String?) {
        if (treeUri == null || _uiState.value.isSubmitting) return
        if (!DeviceFolder.isDeviceStorage(treeUri)) {
            _uiState.update { it.copy(error = UiText.Res(R.string.device_folder_error_not_device)) }
            return
        }
        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            try {
                sessionRepository.connectDeviceFolder(treeUri)
                // 保存すると画面が離れてこのスコープが取り消されうるので、初回の全走査は間を置かずに始める
                // （アプリのスコープで走り、画面が消えても最後まで走る。結果の文言は一覧側に出る）
                refresher.launchRefresh()
                _uiState.update { it.copy(isSubmitting = false) }
                // 同期の制約は取得元で変わる（端末のフォルダにはネットワークの条件をかけない）ので、定期同期を組み直す。
                // 取り消されても次にアプリが前面に出たとき（onAppStart）に組み直される
                try {
                    syncScheduler.schedulePeriodic()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "schedulePeriodic failed", e)
                }
            } catch (e: UnsupportedFolderException) {
                _uiState.update { it.copy(isSubmitting = false, error = UiText.Res(R.string.device_folder_error_not_device)) }
            } catch (e: ServerException) {
                // 例外の文言（英語の内部の文言と URI）は画面に出さず、logcat に残す。SMB の認証の失敗と同じく固定の文言にする
                Log.w(TAG, "pick the device folder failed", e)
                _uiState.update { it.copy(isSubmitting = false, error = UiText.Res(R.string.device_folder_error_failed)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // DataStore の失敗。落とさずに画面へ
                _uiState.update { it.copy(isSubmitting = false, error = UiText.Res(R.string.connect_error_save_failed, e::class.simpleName.orEmpty())) }
            }
        }
    }
}
