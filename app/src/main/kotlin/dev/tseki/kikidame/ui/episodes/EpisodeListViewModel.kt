package dev.tseki.kikidame.ui.episodes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.text.UiText
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LibraryRepository
import dev.tseki.kikidame.domain.LocalStorageUsage
import dev.tseki.kikidame.domain.PlaybackRules
import dev.tseki.kikidame.domain.PlaybackStateRepository
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.RetentionRule
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.download.DownloadProgress
import dev.tseki.kikidame.download.DownloadScheduler
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.playback.NowPlayingState
import dev.tseki.kikidame.sync.LibraryRefresher
import dev.tseki.kikidame.ui.EpisodeListRoute
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import kotlin.time.Clock

@HiltViewModel
class EpisodeListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val library: LibraryRepository,
    private val playbackStates: PlaybackStateRepository,
    private val clock: Clock,
    sessionRepository: SessionRepository,
    private val refresher: LibraryRefresher,
    private val downloads: DownloadRepository,
    private val scheduler: DownloadScheduler,
    nowPlaying: NowPlaying,
) : ViewModel() {
    private val programId = ProgramId(savedStateHandle.toRoute<EpisodeListRoute>().programId)

    val program: StateFlow<Program?> = library.observeProgram(programId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val episodes: StateFlow<List<EpisodeWithState>?> = library.observeEpisodes(programId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /** この番組の手元のファイルの合計（#42）。一覧から数えるので新しいクエリは要らない。null は読み込み前。 */
    val localStorage: StateFlow<LocalStorageUsage?> = episodes
        .map { list -> list?.let(LocalStorageUsage::of) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val canRefresh: StateFlow<Boolean> = sessionRepository.state
        .map { it is SessionState.Ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isRefreshing: StateFlow<Boolean> = refresher.isRefreshing

    /** 裏の同期（定期・起動時）のうち、手動が合流していない間だけ true。トップバーの下に細いバーを出す（#138）。 */
    val isSyncingInBackground: StateFlow<Boolean> = refresher.isSyncingInBackground

    /**
     * 聴いている回（別の番組の回のこともある）。この番組の回なら行にマークを出す。
     * 再生位置（ミニプレイヤー用、1 秒ごとに変わる）は行に要らないので落とし、行を毎秒作り直さない。
     */
    val nowPlaying: StateFlow<NowPlayingState?> = nowPlaying.state.forRows(viewModelScope)

    /** 進行中のダウンロード（各回 ID と割合）。 */
    val progress: StateFlow<DownloadProgress?> = scheduler.progress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Worker が Wi-Fi などの条件待ちで止まっている。 */
    val waitingForNetwork: StateFlow<Boolean> = scheduler.isWaitingForConstraints
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val localMessages = MutableSharedFlow<UiText>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val actions = EpisodeActions(viewModelScope, downloads, localMessages, TAG, "episode action failed")

    /** 更新の結果、この画面での操作の結果、ミニプレイヤーが消えた理由を 1 本にまとめてスナックバーへ。 */
    val messages: Flow<UiText> = merge(refresher.messages, localMessages, nowPlaying.messages)

    /**
     * 各回一覧の「引っ張って更新」= 1 番組の同期（#279）。番組の行が無い・取得元 ID が無いときは全走査に切り替える。
     * `applicationScope` で走らせ、画面を離れても最後まで続ける（#311 の決定 9）。
     */
    fun refresh() = refresher.launchSyncProgram(programId, orFullSync = true)

    // --- 同期対象・保持ルール（変えている間は保存するだけ。シートを閉じたとき、変わっていればこの番組を同期する。#311） ---

    /**
     * 設定の書き込みと、シートを閉じたときの読み直しを、この順に並べる。チップを押してすぐ閉じても、書き込みが済んでから比べる
     * （書き込みは押した時点で鍵を取るので、後から来る閉じたときの読み直しはその後になる）。
     */
    private val settingsLock = Mutex()

    /** シートを開いたときの同期の設定。null ならシートは開いていない（か、開いたときに番組が読めていなかった）。 */
    private var openedWith: EffectiveSyncSettings? = null

    /** 同期のシートを開いた。今の同期の設定を、閉じたときに比べるために覚える。 */
    fun onSyncSheetOpened() {
        openedWith = program.value?.let(::EffectiveSyncSettings)
    }

    /**
     * 同期のシートを閉じた（閉じる経路は問わない。#311 の決定 1）。開いたときから同期の設定が変わっていれば、この番組だけを同期する。
     * 同期は `applicationScope` で走り、走っている同期があれば終わるのを待つ（[LibraryRefresher.launchSyncProgramAfterRunning]）。
     * 設定を書き終えてから比べるため、比べるのは同期を走らせる直前（待った後）にする。
     */
    fun onSyncSheetClosed() {
        val before = openedWith ?: return
        openedWith = null
        refresher.launchSyncProgramAfterRunning(programId) {
            val after = settingsLock.withLock { library.getProgram(programId) } ?: return@launchSyncProgramAfterRunning false
            EffectiveSyncSettings(after) != before
        }
    }

    /** OFF にすると、シートを閉じたときの同期で消える回の数。null なら確認ダイアログは出ていない。 */
    private val _pendingDisable = MutableStateFlow<Int?>(null)
    val pendingDisable: StateFlow<Int?> = _pendingDisable

    /** ON にするとき、上限が未設定なら既定の 3 を入れる（「上限なし」は ON にした後で選ぶ）。 */
    fun setSyncEnabled(enabled: Boolean) = actOnSettings {
        val current = library.getProgram(programId) ?: return@actOnSettings
        if (enabled) {
            val rule = current.retentionRule.let { if (it.keepLatest == null) it.copy(keepLatest = DEFAULT_KEEP_LATEST) else it }
            library.updateSync(programId, syncEnabled = true, rule)
        } else {
            val count = library.countUnpinnedLocalFiles(programId)
            if (count == 0) library.updateSync(programId, syncEnabled = false, current.retentionRule) else _pendingDisable.value = count
        }
    }

    fun confirmDisableSync() = actOnSettings {
        _pendingDisable.value = null
        val current = library.getProgram(programId) ?: return@actOnSettings
        library.updateSync(programId, syncEnabled = false, current.retentionRule)
    }

    fun cancelDisableSync() {
        _pendingDisable.value = null
    }

    /** 番組を手元から消した（画面は戻る）。 */
    private val _programRemoved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val programRemoved: Flow<Unit> = _programRemoved

    /** 消失した番組・取得元 ID の無い番組の「この番組を手元から消す」。番組・各回・ファイル・再生位置をすべて消す。 */
    fun removeProgram() = act {
        downloads.removeProgram(programId)
        _programRemoved.tryEmit(Unit)
    }

    /** よく聴くの印を付ける／外す（表示にだけ効く）。 */
    fun setStarred(starred: Boolean) = act { library.setStarred(programId, starred) }
    fun setKeepLatest(keepLatest: Int?) = updateRule { it.copy(keepLatest = keepLatest) }

    fun setDeleteAfterPlayed(value: Boolean) = updateRule { it.copy(deleteAfterPlayed = value) }

    private fun updateRule(transform: (RetentionRule) -> RetentionRule) = actOnSettings {
        val current = library.getProgram(programId) ?: return@actOnSettings
        library.updateSync(programId, current.syncEnabled, transform(current.retentionRule))
    }

    /** 同期の設定の書き込み。[settingsLock] の中で行う（シートを閉じたときの比べより先に済ませる）。 */
    private fun actOnSettings(block: suspend () -> Unit) = act { settingsLock.withLock { block() } }

    fun setPlayed(episodeId: EpisodeId, played: Boolean) {
        viewModelScope.launch {
            playbackStates.update(episodeId) { PlaybackRules.setPlayed(it, played, clock.now()) }
        }
    }

    fun download(episodeId: EpisodeId) = act { scheduler.download(episodeId) }

    fun cancel(episodeId: EpisodeId) = act { scheduler.cancel(episodeId) }

    fun retry(episodeId: EpisodeId) = act { scheduler.retry(episodeId) }

    fun pin(episodeId: EpisodeId) = actions.pin(episodeId)

    fun unpin(episodeId: EpisodeId) = actions.unpin(episodeId)

    fun deleteLocal(episodeId: EpisodeId) = act {
        val scope = downloads.deleteLocal(episodeId)
        val syncEnabled = program.value?.syncEnabled == true
        localMessages.tryEmit(scope.toMessage(syncEnabled))
    }

    /** Room / ファイル I/O の失敗で落とさず、文言にして出す（[EpisodeActions.launch]）。 */
    private fun act(block: suspend () -> Unit) = actions.launch(block)

    /**
     * 同期に効く番組の設定。同期対象でない番組の同期は保持ルールを見ない（`SyncPlanner.planProgram`）ので、OFF のときは保持ルールを
     * 比べない。ON にすると上限の既定の 3 が入るため、OFF の番組を ON にして OFF に戻しても、保持ルールの違いで「変わった」とはしない
     * （#311 の決定 1）。
     */
    private data class EffectiveSyncSettings(val syncEnabled: Boolean, val retentionRule: RetentionRule?) {
        constructor(program: Program) : this(program.syncEnabled, program.retentionRule.takeIf { program.syncEnabled })
    }

    companion object {
        private const val TAG = "EpisodeListViewModel"
        const val DEFAULT_KEEP_LATEST = 3

        /** 「最新 N 回まで保持」の選択肢。null は上限なし。 */
        val KEEP_LATEST_CHOICES: List<Int?> = listOf(1, 3, 5, 10, 20, null)
    }
}
