package dev.tseki.kikidame.ui.episodes

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.ui.UiText
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LibraryRepository
import dev.tseki.kikidame.domain.LocalDeletionScope
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    /**
     * 聴いている回（別の番組の回のこともある）。この番組の回なら行にマークを出す。
     * 再生位置（ミニプレイヤー用、1 秒ごとに変わる）は行に要らないので落とし、行を毎秒作り直さない。
     */
    val nowPlaying: StateFlow<NowPlayingState?> = nowPlaying.state
        .map { it?.copy(positionMs = 0, durationMs = 0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), nowPlaying.state.value?.copy(positionMs = 0, durationMs = 0))

    /** 進行中のダウンロード（各回 ID と割合）。 */
    val progress: StateFlow<DownloadProgress?> = scheduler.progress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Worker が Wi-Fi などの条件待ちで止まっている。 */
    val waitingForNetwork: StateFlow<Boolean> = scheduler.isWaitingForConstraints
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val localMessages = MutableSharedFlow<UiText>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 更新の結果、この画面での操作の結果、ミニプレイヤーが消えた理由を 1 本にまとめてスナックバーへ。 */
    val messages: Flow<UiText> = merge(refresher.messages, localMessages, nowPlaying.messages)

    /** 各回一覧の「引っ張って更新」= この番組だけ取り込む（#12）。削除はしない。 */
    fun refresh() {
        viewModelScope.launch { refresher.refreshProgram(programId) }
    }

    /** ボトムシートの「この番組を今すぐ同期」= 1 番組の同期。 */
    fun syncNow() {
        viewModelScope.launch { refresher.syncProgram(programId) }
    }

    // --- 同期対象・保持ルール（適用は次の同期。ここでは保存するだけ） ---

    /** OFF にすると次の同期で消える回の数。null なら確認ダイアログは出ていない。 */
    private val _pendingDisable = MutableStateFlow<Int?>(null)
    val pendingDisable: StateFlow<Int?> = _pendingDisable

    /** ON にするとき、上限が未設定なら既定の 3 を入れる（「上限なし」は ON にした後で選ぶ）。 */
    fun setSyncEnabled(enabled: Boolean) = act {
        val current = program.value ?: library.observeProgram(programId).first() ?: return@act
        if (enabled) {
            val rule = current.retentionRule.let { if (it.keepLatest == null) it.copy(keepLatest = DEFAULT_KEEP_LATEST) else it }
            library.updateSync(programId, syncEnabled = true, rule)
        } else {
            val count = library.countUnpinnedLocalFiles(programId)
            if (count == 0) library.updateSync(programId, syncEnabled = false, current.retentionRule) else _pendingDisable.value = count
        }
    }

    fun confirmDisableSync() = act {
        _pendingDisable.value = null
        val current = library.observeProgram(programId).first() ?: return@act
        library.updateSync(programId, syncEnabled = false, current.retentionRule)
    }

    fun cancelDisableSync() {
        _pendingDisable.value = null
    }

    /** 番組を手元から消した（画面は戻る）。 */
    private val _programRemoved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val programRemoved: Flow<Unit> = _programRemoved

    /** 消失した番組・サーバ ID の無い番組の「この番組を手元から消す」。番組・各回・ファイル・再生位置をすべて消す。 */
    fun removeProgram() = act {
        downloads.removeProgram(programId)
        _programRemoved.tryEmit(Unit)
    }

    /** よく聴くの印を付ける／外す（表示にだけ効く）。 */
    fun setStarred(starred: Boolean) = act { library.setStarred(programId, starred) }
    fun setKeepLatest(keepLatest: Int?) = updateRule { it.copy(keepLatest = keepLatest) }

    fun setDeleteAfterPlayed(value: Boolean) = updateRule { it.copy(deleteAfterPlayed = value) }

    private fun updateRule(transform: (RetentionRule) -> RetentionRule) = act {
        val current = library.observeProgram(programId).first() ?: return@act
        library.updateSync(programId, current.syncEnabled, transform(current.retentionRule))
    }

    fun setPlayed(episodeId: EpisodeId, played: Boolean) {
        viewModelScope.launch {
            playbackStates.update(episodeId) { PlaybackRules.setPlayed(it, played, clock.now()) }
        }
    }

    fun download(episodeId: EpisodeId) = act { scheduler.download(episodeId) }

    fun cancel(episodeId: EpisodeId) = act { scheduler.cancel(episodeId) }

    fun retry(episodeId: EpisodeId) = act { scheduler.retry(episodeId) }

    fun unpin(episodeId: EpisodeId) = act {
        downloads.unpin(episodeId)
        localMessages.tryEmit(UiText.Res(R.string.episode_list_msg_unpinned))
    }

    fun deleteLocal(episodeId: EpisodeId) = act {
        val scope = downloads.deleteLocal(episodeId)
        val syncEnabled = program.value?.syncEnabled == true
        localMessages.tryEmit(
            when (scope) {
                // 同期対象なら保持すべき回は次の同期で落とし直される（handoff「手動削除と同期の往復」）
                LocalDeletionScope.FILE_ONLY ->
                    if (syncEnabled) UiText.Res(R.string.episode_list_msg_file_deleted_synced)
                    else UiText.Res(R.string.episode_list_msg_file_deleted)
                LocalDeletionScope.EPISODE -> UiText.Res(R.string.episode_list_msg_episode_removed)
            },
        )
    }

    /** Room / ファイル I/O の失敗で落とさず、文言にして出す。 */
    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "episode action failed", e)
                localMessages.tryEmit(UiText.Res(R.string.episode_list_msg_action_failed, e::class.simpleName.orEmpty()))
            }
        }
    }

    companion object {
        private const val TAG = "EpisodeListViewModel"
        const val DEFAULT_KEEP_LATEST = 3

        /** 「最新 N 回まで保持」の選択肢。null は上限なし。 */
        val KEEP_LATEST_CHOICES: List<Int?> = listOf(1, 3, 5, 10, 20, null)
    }
}
