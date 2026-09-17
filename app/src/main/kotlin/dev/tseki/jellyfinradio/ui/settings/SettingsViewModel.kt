package dev.tseki.jellyfinradio.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.jellyfinradio.domain.AppSettingsRepository
import dev.tseki.jellyfinradio.domain.LocalDataReset
import dev.tseki.jellyfinradio.domain.SessionRepository
import dev.tseki.jellyfinradio.domain.SessionState
import dev.tseki.jellyfinradio.download.DownloadScheduler
import dev.tseki.jellyfinradio.seed.SeedLocalLibrary
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
    private val reset: LocalDataReset,
    private val seed: SeedLocalLibrary,
    private val settings: AppSettingsRepository,
    private val scheduler: DownloadScheduler,
) : ViewModel() {
    val wifiOnly: StateFlow<Boolean> = settings.wifiOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    /** 変更したら、条件待ちの Worker を新しい条件で組み直す（実行中の転送は切らない）。 */
    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch {
            try {
                settings.setWifiOnly(value)
                scheduler.reschedule()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "設定の保存に失敗しました: ${e::class.simpleName}"
            }
        }
    }

    val session: StateFlow<SessionState?> = sessionRepository.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val seedRoot: String? get() = seed.root?.absolutePath

    /** 認証情報だけ消す。手元の番組・各回・再生位置は残る。 */
    fun signOut() {
        viewModelScope.launch { sessionRepository.signOut() }
    }

    /** 「別のサーバに接続」。手元のデータを全部消してから接続画面へ（セッション状態の変化で遷移する）。 */
    fun resetAndConnectElsewhere() {
        viewModelScope.launch { reset.resetAll() }
    }

    fun runSeed() {
        viewModelScope.launch {
            val r = seed.run()
            _message.value = "番組 ${r.addedPrograms} / 各回 ${r.addedEpisodes} を追加（既存 ${r.skippedEpisodes} はスキップ）"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
