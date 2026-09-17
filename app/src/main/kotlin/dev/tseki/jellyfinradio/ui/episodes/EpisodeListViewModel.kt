package dev.tseki.jellyfinradio.ui.episodes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tseki.jellyfinradio.domain.DownloadRepository
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.EpisodeWithState
import dev.tseki.jellyfinradio.domain.LibraryRepository
import dev.tseki.jellyfinradio.domain.LocalDeletionScope
import dev.tseki.jellyfinradio.domain.PlaybackRules
import dev.tseki.jellyfinradio.domain.PlaybackStateRepository
import dev.tseki.jellyfinradio.domain.Program
import dev.tseki.jellyfinradio.domain.ProgramId
import dev.tseki.jellyfinradio.domain.SessionRepository
import dev.tseki.jellyfinradio.domain.SessionState
import dev.tseki.jellyfinradio.download.DownloadProgress
import dev.tseki.jellyfinradio.download.DownloadScheduler
import dev.tseki.jellyfinradio.sync.LibraryRefresher
import dev.tseki.jellyfinradio.ui.EpisodeListRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    library: LibraryRepository,
    private val playbackStates: PlaybackStateRepository,
    private val clock: Clock,
    sessionRepository: SessionRepository,
    private val refresher: LibraryRefresher,
    private val downloads: DownloadRepository,
    private val scheduler: DownloadScheduler,
) : ViewModel() {
    private val programId = ProgramId(savedStateHandle.toRoute<EpisodeListRoute>().programId)

    val program: StateFlow<Program?> = library.observeProgram(programId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val episodes: StateFlow<List<EpisodeWithState>?> = library.observeEpisodes(programId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val canRefresh: StateFlow<Boolean> = sessionRepository.state
        .map { it is SessionState.Ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isRefreshing: StateFlow<Boolean> = refresher.isRefreshing

    /** 進行中のダウンロード（各回 ID と割合）。 */
    val progress: StateFlow<DownloadProgress?> = scheduler.progress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Worker が Wi-Fi などの条件待ちで止まっている。 */
    val waitingForNetwork: StateFlow<Boolean> = scheduler.isWaitingForConstraints
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val localMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** 更新の結果と、この画面での操作の結果を 1 本にまとめてスナックバーへ。 */
    val messages: Flow<String> = merge(refresher.messages, localMessages)

    fun refresh() {
        viewModelScope.launch { refresher.refresh() }
    }

    fun setPlayed(episodeId: EpisodeId, played: Boolean) {
        viewModelScope.launch {
            playbackStates.update(episodeId) { PlaybackRules.setPlayed(it, played, clock.now()) }
        }
    }

    fun download(episodeId: EpisodeId) {
        viewModelScope.launch { scheduler.download(episodeId) }
    }

    fun cancel(episodeId: EpisodeId) {
        viewModelScope.launch { scheduler.cancel(episodeId) }
    }

    fun retry(episodeId: EpisodeId) {
        viewModelScope.launch { scheduler.retry(episodeId) }
    }

    fun unpin(episodeId: EpisodeId) {
        viewModelScope.launch {
            downloads.unpin(episodeId)
            localMessages.tryEmit("固定を外しました。保持ルールの対象になります")
        }
    }

    fun deleteLocal(episodeId: EpisodeId) {
        viewModelScope.launch {
            val scope = downloads.deleteLocal(episodeId)
            localMessages.tryEmit(
                when (scope) {
                    LocalDeletionScope.FILE_ONLY -> "ファイルを削除しました。再生位置は残っています"
                    LocalDeletionScope.EPISODE -> "この回はサーバに無いため、一覧からも消しました"
                },
            )
        }
    }
}
