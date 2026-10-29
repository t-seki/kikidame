package dev.tseki.kikidame.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.kikidame.R
import dev.tseki.kikidame.data.repository.PlaybackSharing
import dev.tseki.kikidame.domain.AppSettingsRepository
import dev.tseki.kikidame.domain.PlaybackShareStatus
import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.UnmatchedPrograms
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.domain.ThemeMode
import dev.tseki.kikidame.domain.LibraryRepository
import dev.tseki.kikidame.domain.LocalStorageUsage
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.download.DownloadScheduler
import dev.tseki.kikidame.ui.source.SourceChanger
import dev.tseki.kikidame.sync.LibraryRefresher
import dev.tseki.kikidame.sync.SyncScheduler
import dev.tseki.kikidame.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
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
    private val downloads: DownloadRepository,
    private val nowPlaying: NowPlaying,
    library: LibraryRepository,
    playbackSharing: PlaybackSharing,
) : ViewModel() {
    /** 再生の共有の最後の送受信（ADR 0010 の決定 8、#355）。Jellyfin のブックのライブラリでないときは null（行を出さない）。 */
    val playbackShare: StateFlow<PlaybackShareStatus?> = playbackSharing.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 手元のファイルの合計（#42）。null は読み込み前。 */
    val localStorage: StateFlow<LocalStorageUsage?> = library.observeLocalStorage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /** 取得元に見つからない番組の件数（#317）。0 番組なら設定の行を出さない。 */
    val unmatched: StateFlow<UnmatchedPrograms> = downloads.observeUnmatchedPrograms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UnmatchedPrograms(0, 0, 0))

    /** 同期の間（利用者の操作でも裏でも）は、取得元に見つからない番組を消す行を無効にする。 */
    val isSyncing: StateFlow<Boolean> = combine(refresher.isRefreshing, refresher.isSyncingInBackground) { a, b -> a || b }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 確認のダイアログに出す件数（聴いている回の番組を除いたもの）。null なら出していない。 */
    private val _removePreview = MutableStateFlow<UnmatchedPrograms?>(null)
    val removePreview: StateFlow<UnmatchedPrograms?> = _removePreview

    /** ダイアログを出した時点の対象の番組。確定ではこの中から消す（開いている間に新しく見つからなくなった番組は消さない）。 */
    private var pendingIds: List<ProgramId> = emptyList()

    /**
     * 行を押したとき。聴いている回の番組を除いて数え、消すものがあれば確認を出す。除くと 0 番組なら（対象が聴いている番組だけ）
     * 確認は出さず、残したことだけ知らせる。
     */
    fun requestRemoveUnmatched() {
        if (isSyncing.value) return
        launchAction {
            val preview = downloads.unmatchedPrograms(nowPlaying.excludedFromSync)
            if (preview.counts.programCount > 0) {
                pendingIds = preview.programIds
                _removePreview.value = preview.counts
            } else {
                val kept = downloads.unmatchedPrograms(null).counts.programCount
                if (kept > 0) _message.value = keptMessage(kept)
            }
        }
    }

    fun cancelRemoveUnmatched() {
        _removePreview.value = null
        pendingIds = emptyList()
    }

    /** 確認の後。ダイアログに出した番組だけを消し、聴いている回の番組は消さずに残して知らせる。同期中なら消さずに知らせる。 */
    fun confirmRemoveUnmatched() {
        val ids = pendingIds
        cancelRemoveUnmatched()
        if (isSyncing.value) {
            _message.value = UiText.Res(R.string.settings_remove_unmatched_disabled)
            return
        }
        launchAction {
            val result = downloads.removeUnmatchedPrograms(ids, nowPlaying.excludedFromSync)
            if (result.kept > 0) _message.value = keptMessage(result.kept)
        }
    }

    private fun keptMessage(kept: Int) = UiText.Plural(R.plurals.settings_remove_unmatched_kept, kept)

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "remove unmatched programs", e)
                _message.value = UiText.Res(R.string.episode_list_msg_action_failed, e::class.simpleName.orEmpty())
            }
        }
    }

    val wifiOnly: StateFlow<Boolean> = settings.wifiOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    /** 変更したら、条件待ちの Worker（ダウンロード・定期同期）を新しい条件で組み直す（実行中の転送は切らない）。 */
    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch {
            try {
                settings.setWifiOnly(value)
                scheduler.reschedule()
                syncScheduler.schedulePeriodic()
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

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
