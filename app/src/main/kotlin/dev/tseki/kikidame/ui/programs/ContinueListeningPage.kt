package dev.tseki.kikidame.ui.programs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.ui.UiText
import dev.tseki.kikidame.ui.episodes.EpisodeActionsSheet
import dev.tseki.kikidame.ui.episodes.EpisodeRow
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * 「続きから」タブ（#151）の中身。行のタップは各回一覧と同じく再生画面を開いて続きから再生する（キューはその番組の手元の回）。
 * 空なら案内を出す。
 */
@Composable
internal fun ContinueListeningPage(
    viewModel: ContinueListeningViewModel,
    listState: LazyListState,
    onEpisodeClick: (EpisodeId) -> Unit,
    onEpisodeDetails: (ProgramId, EpisodeId) -> Unit,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    var sheetFor by remember { mutableStateOf<EpisodeId?>(null) }
    val list = items
    when {
        list == null -> Box(Modifier.fillMaxSize())
        list.isEmpty() -> PageMessage(stringResource(R.string.program_list_continue_empty))
        else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(list, key = { it.episode.episode.id.value }) { item ->
                val id = item.episode.episode.id
                EpisodeRow(
                    item = item.episode,
                    nowPlaying = nowPlaying?.takeIf { it.episodeId == id },
                    supportingText = item.toSupportingText(),
                    onClick = { onEpisodeClick(id) },
                    onLongClick = { sheetFor = id },
                    trailing = {
                        ContinueListeningTrailing(
                            played = item.episode.playback?.played == true,
                            onTogglePlayed = { viewModel.setPlayed(id, item.episode.playback?.played != true) },
                        )
                    },
                )
                HorizontalDivider()
            }
        }
    }
    // 再生済みにした・消した回は一覧から消えるので、シートの対象も一覧から引き直す（消えたらシートも閉じる）
    val target = sheetFor?.let { id -> list?.firstOrNull { it.episode.episode.id == id } }
    if (target != null) {
        val episode = target.episode.episode
        EpisodeActionsSheet(
            item = target.episode,
            syncEnabled = target.program?.syncEnabled == true,
            onDownload = null, // 「続きから」の回は必ず手元にある
            onDismiss = { sheetFor = null },
            onUnpin = { viewModel.unpin(episode.id) },
            onDelete = { viewModel.deleteLocal(episode.id) },
            onDetails = { sheetFor = null; onEpisodeDetails(episode.programId, episode.id) },
        )
    }
}

/**
 * 「続きから」の行の右端。回は必ず手元にあるので、ダウンロードの状態ではなく常に再生済みの切り替え。
 */
@Composable
private fun ContinueListeningTrailing(played: Boolean, onTogglePlayed: () -> Unit) {
    IconButton(onClick = onTogglePlayed) {
        if (played) {
            Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.episode_list_played_tap))
        } else {
            Icon(Icons.Outlined.Circle, contentDescription = stringResource(R.string.episode_list_unplayed_tap))
        }
    }
}

/**
 * 行の補足（#151）: `番組名 · 残り N 分`。残りは次に再生したとき始まる位置（[dev.tseki.kikidame.domain.EpisodeWithState.resumePosition]。
 * バーと同じ）から末尾までで、分に切り上げる（「残り 0 分」は出さない）。尺が分からない回（runtime 0）は残りを省いて番組名だけ。
 */
internal fun ContinueListeningViewModel.Item.toSupportingText(): UiText {
    val runtime = episode.episode.runtime
    val remaining = if (runtime > Duration.ZERO) {
        val left = (runtime - (episode.resumePosition ?: Duration.ZERO)).coerceAtLeast(Duration.ZERO)
        val minutes = ceil(left.toDouble(DurationUnit.MINUTES)).toInt().coerceAtLeast(1)
        UiText.Plural(R.plurals.program_list_continue_remaining, minutes, minutes)
    } else {
        null
    }
    return UiText.Joined(listOfNotNull(program?.name?.let(UiText::Plain), remaining), UiText.Plain(" · "))
}
