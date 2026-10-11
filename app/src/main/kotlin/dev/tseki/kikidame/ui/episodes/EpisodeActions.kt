package dev.tseki.kikidame.ui.episodes

import android.util.Log
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.LocalDeletionScope
import dev.tseki.kikidame.playback.NowPlayingState
import dev.tseki.kikidame.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/*
 * 各回一覧（EpisodeListViewModel）と「続きから」（ContinueListeningViewModel）が共通で使う、各回の行の操作（#230）。
 * 2 つの画面は同じ行（[EpisodeRow]）と同じボトムシート（[EpisodeActionsSheet]）を出すので、操作の結果の文言と失敗の扱いも揃える。
 * 再生済みの切り替え（setPlayed）は画面ごとに違う（「続きから」だけ失敗を文言にし、［元に戻す］の合図を出す）ので、ここには置かない。
 */

/**
 * 「ファイルを削除」の結果の文言。[syncEnabled] はその回の番組が同期対象か。
 * 同期対象なら保持すべき回は次の同期で落とし直される（handoff「手動削除と同期の往復」）ので、そう伝える。
 */
internal fun LocalDeletionScope.toMessage(syncEnabled: Boolean): UiText = when (this) {
    LocalDeletionScope.FILE_ONLY ->
        if (syncEnabled) UiText.Res(R.string.episode_list_msg_file_deleted_synced)
        else UiText.Res(R.string.episode_list_msg_file_deleted)
    LocalDeletionScope.EPISODE -> UiText.Res(R.string.episode_list_msg_episode_removed)
}

/** 行に要る分だけの聴いている回。再生位置（ミニプレイヤー用、1 秒ごとに変わる）を 0 に落とし、行を毎秒作り直さない。 */
internal fun NowPlayingState?.forRows(): NowPlayingState? = this?.copy(positionMs = 0, durationMs = 0)

/** [forRows] を当てた [StateFlow]。初期値も今の値から作るので、画面を開いた直後から印が出る。 */
internal fun StateFlow<NowPlayingState?>.forRows(scope: CoroutineScope): StateFlow<NowPlayingState?> =
    map { it.forRows() }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), value.forRows())

/**
 * 各回の操作を [scope] で走らせ、Room / ファイル I/O の失敗で落とさず文言にして [messages] へ流す。
 * [logTag] と [logMessage] はログに出す画面ごとの名前。
 */
internal class EpisodeActions(
    private val scope: CoroutineScope,
    private val downloads: DownloadRepository,
    private val messages: MutableSharedFlow<UiText>,
    private val logTag: String,
    private val logMessage: String,
) {
    fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(logTag, logMessage, e)
                messages.tryEmit(UiText.Res(R.string.episode_list_msg_action_failed, e::class.simpleName.orEmpty()))
            }
        }
    }

    /** 固定を外し、外したことを文言で知らせる。 */
    fun unpin(episodeId: EpisodeId) = launch {
        downloads.unpin(episodeId)
        messages.tryEmit(UiText.Res(R.string.episode_list_msg_unpinned))
    }
}
