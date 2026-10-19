package dev.tseki.kikidame.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.LocalEpisode
import dev.tseki.kikidame.domain.LocalStorageRepository
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.sync.LibraryRefresher
import dev.tseki.kikidame.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 選んだ回（#335）。[episodes] は選んで見えている回だけ（聴いている回と、絞り込みで隠れた回は入らない）。
 * 確認のダイアログと「N 回を消す」のボタンに出す数と容量はここから取る。
 */
data class StorageSelection(val episodes: List<LocalEpisode>) {
    val count: Int get() = episodes.size
    val totalBytes: Long get() = episodes.sumOf { it.sizeBytes }

    /** 取得元 ID の無い回（消すと一覧からも消え、戻せない回）の数。 */
    val unrecoverableCount: Int get() = episodes.count { !it.hasSourceItemId }

    /** 同期対象の番組の回を含むか（確認に「次の同期で落とし直されます」の一般の文を足す）。 */
    val hasSyncedProgram: Boolean get() = episodes.any { it.programSyncEnabled }

    companion object {
        val EMPTY = StorageSelection(emptyList())
    }
}

/**
 * 手元の回を選んで消す画面（内訳の画面の「すべての回」と、番組ごとの一覧。#335）が共通で持つ、選択と削除。
 * 行を押すとチェックが付く・外れる。聴いている回（[NowPlaying.excludedFromSync] と同じ定義）は選べず、消す時点でももう一度除く
 * （選んだ後に再生を始めた場合に備える。#317 と同じ考え方）。同期の間（操作による同期も裏の同期も）は消せない。
 */
abstract class StorageSelectionViewModel(
    private val storage: LocalStorageRepository,
    private val nowPlaying: NowPlaying,
    refresher: LibraryRefresher,
) : ViewModel() {
    /** 今見えている回（絞り込みの後）。null は読み込み前。 */
    abstract val visibleEpisodes: StateFlow<List<LocalEpisode>?>

    private val selectedIds = MutableStateFlow<Set<EpisodeId>>(emptySet())
    val selected: StateFlow<Set<EpisodeId>> = selectedIds

    /** 聴いている回（プレイヤーに載っていて、聴き終えていない回）。行の背景を変え、チェックボックスを無効にする。 */
    val listening: StateFlow<EpisodeId?> = nowPlaying.state.map { it?.takeIf { s -> !s.isEnded }?.episodeId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), nowPlaying.excludedFromSync)

    val isSyncing: StateFlow<Boolean> = combine(refresher.isRefreshing, refresher.isSyncingInBackground) { a, b -> a || b }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 選んで見えている回。サブクラスの初期化の後に [visibleEpisodes] を読むので lazy。 */
    val selection: StateFlow<StorageSelection> by lazy {
        combine(visibleEpisodes, selectedIds, listening) { visible, ids, playing ->
            StorageSelection(visible.orEmpty().filter { it.episodeId in ids && it.episodeId != playing })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageSelection.EMPTY)
    }

    /** 確認のダイアログに出している選択。null なら出していない。確定ではこの回を消す。 */
    private val _confirm = MutableStateFlow<StorageSelection?>(null)
    val confirm: StateFlow<StorageSelection?> = _confirm

    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message

    /** 消している間だけ true。番組ごとの一覧が 0 回になって戻るのを、消した後の文言（[message]）ができるまで待つのに使う。 */
    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting

    fun toggle(episodeId: EpisodeId) {
        if (episodeId == listening.value) return
        selectedIds.update { if (episodeId in it) it - episodeId else it + episodeId }
    }

    /** 手元から消えた回（消した・同期で消えた）を選択から外す。後で同じ回を落とし直しても、選んだままにならないように。 */
    protected fun keepOnly(present: Flow<List<LocalEpisode>>) {
        viewModelScope.launch {
            present.collect { list ->
                val ids = list.mapTo(HashSet()) { it.episodeId }
                selectedIds.update { selected -> selected.filterTo(HashSet()) { it in ids } }
            }
        }
    }

    fun requestDelete() {
        if (isSyncing.value) return
        val current = selection.value
        if (current.count > 0) _confirm.value = current
    }

    fun cancelDelete() {
        _confirm.value = null
    }

    /** 確認の後。ダイアログに出した回を消し、聴いている回は消さずに残して知らせる。同期中なら消さずに知らせる。 */
    fun confirmDelete() {
        val chosen = _confirm.value ?: return
        _confirm.value = null
        if (isSyncing.value) {
            _message.value = UiText.Res(R.string.storage_delete_disabled)
            return
        }
        val ids = chosen.episodes.map { it.episodeId }
        _isDeleting.value = true
        viewModelScope.launch {
            try {
                val result = storage.deleteLocalEpisodes(ids, nowPlaying.excludedFromSync)
                selectedIds.update { it - ids.toSet() }
                _message.value = deletedMessage(result.deleted, result.kept)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "delete local episodes", e)
                _message.value = UiText.Res(R.string.episode_list_msg_action_failed, e::class.simpleName.orEmpty())
            } finally {
                _isDeleting.value = false
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private companion object {
        const val TAG = "StorageSelection"
    }
}

/** 消した後のスナックバー。聴いている回を残したときは、#317 の「聴いている番組 N 件は残しました」に倣って続けて知らせる。 */
internal fun deletedMessage(deleted: Int, kept: Int): UiText? {
    val parts = listOfNotNull(
        UiText.Plural(R.plurals.storage_msg_deleted, deleted).takeIf { deleted > 0 },
        UiText.Plural(R.plurals.storage_msg_kept, kept).takeIf { kept > 0 },
    )
    return when (parts.size) {
        0 -> null
        1 -> parts.single()
        else -> UiText.Joined(parts, UiText.Res(R.string.storage_msg_separator))
    }
}
