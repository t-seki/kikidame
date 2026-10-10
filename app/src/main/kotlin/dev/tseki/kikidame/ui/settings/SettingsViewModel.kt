package dev.tseki.kikidame.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.AppSettingsRepository
import dev.tseki.kikidame.domain.ThemeMode
import dev.tseki.kikidame.domain.LibraryRepository
import dev.tseki.kikidame.domain.LocalStorageUsage
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.download.DownloadScheduler
import dev.tseki.kikidame.ui.source.SourceChanger
import dev.tseki.kikidame.sync.LibraryRefresher
import dev.tseki.kikidame.sync.SyncScheduler
import dev.tseki.kikidame.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val settings: AppSettingsRepository,
    private val scheduler: DownloadScheduler,
    private val syncScheduler: SyncScheduler,
    private val sourceChanger: SourceChanger,
    private val refresher: LibraryRefresher,
    library: LibraryRepository,
) : ViewModel() {
    /** 手元のファイルの合計（#42）。null は読み込み前。 */
    val localStorage: StateFlow<LocalStorageUsage?> = library.observeLocalStorage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val wifiOnly: StateFlow<Boolean> = settings.wifiOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    /** 変更したら、条件待ちの Worker（ダウンロード・定期同期）を新しい条件で組み直す（実行中の転送は切らない）。 */
    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch {
            try {
                settings.setWifiOnly(value)
                scheduler.reschedule()
                syncScheduler.reschedule()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = UiText.Res(R.string.common_error_save_settings, e::class.simpleName.orEmpty())
            }
        }
    }

    /** テーマ（#62）。null は読み込み前（チップは出すが無効にする）。 */
    val themeMode: StateFlow<ThemeMode?> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun setThemeMode(value: ThemeMode) {
        viewModelScope.launch {
            try {
                settings.setThemeMode(value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = UiText.Res(R.string.common_error_save_settings, e::class.simpleName.orEmpty())
            }
        }
    }
    val session: StateFlow<SessionState?> = sessionRepository.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message

    /** 認証情報だけ消す。手元の番組・各回・再生位置は残る。 */
    fun signOut() {
        viewModelScope.launch {
            // 走っている同期を止めてから認証情報を消す（途中で認証が消えた走査の結果が残らないように）
            refresher.cancelAndAwait()
            sessionRepository.signOut()
        }
    }

    /**
     * 「取得元を変える」。再生とダウンロード・同期の Worker を止めてから、手元のデータと音声ファイルを全部消して取得元の選択画面へ
     * （セッション状態の変化で遷移する）。止めずに消すと、開いているファイルを消したり、Worker が行を作り直したりする（#50）。
     */
    fun resetAndConnectElsewhere() {
        viewModelScope.launch { sourceChanger.changeSource() }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
