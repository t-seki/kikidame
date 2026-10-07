package dev.tseki.kikidame.ui.programs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.playback.NowPlayingState
import dev.tseki.kikidame.ui.UiText
import dev.tseki.kikidame.ui.resolve
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
                ContinueListeningRow(
                    item = item,
                    nowPlaying = nowPlaying?.takeIf { it.episodeId == id },
                    onClick = { onEpisodeClick(id) },
                    onLongClick = { sheetFor = id },
                    onTogglePlayed = { viewModel.setPlayed(id, item.episode.playback?.played != true) },
                )
                HorizontalDivider()
            }
        }
    }
    // 再生済みにした・消した回は一覧から消えるので、シートの対象も一覧から引き直す（消えたらシートも閉じる）
    val target = sheetFor?.let { id -> list?.firstOrNull { it.episode.episode.id == id } }
    if (target != null) {
        val episode = target.episode.episode
        ContinueListeningActionsSheet(
            item = target,
            onDismiss = { sheetFor = null },
            onUnpin = { viewModel.unpin(episode.id) },
            onDelete = { viewModel.deleteLocal(episode.id) },
            onDetails = { sheetFor = null; onEpisodeDetails(episode.programId, episode.id) },
        )
    }
}

/** タイトル前の印の大きさと、印とタイトルの間隔（各回一覧と同じ）。 */
private val MARK_SIZE = 14.dp
private val MARK_GAP = 4.dp

/**
 * 1 行目に各回のタイトル、2 行目に「番組名 · 残り N 分」、その下に再生位置のバー（各回一覧と同じ）。
 * 印（固定・再生中／一時停止）と聴いている回の背景、右端の再生済みの切り替えも各回一覧の行と同じ。
 * 「続きから」の回は必ず手元にあるので、右端はダウンロードの状態ではなく常に再生済みの切り替え。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueListeningRow(
    item: ContinueListeningViewModel.Item,
    nowPlaying: NowPlayingState?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTogglePlayed: () -> Unit,
) {
    val episode = item.episode
    val played = episode.playback?.played == true
    val resume = episode.resumePosition
    val runtime = episode.episode.runtime
    val local = episode.localFile
    ListItem(
        modifier = Modifier.combinedClickable(onClick = { if (episode.isPlayable) onClick() }, onLongClick = onLongClick),
        colors = ListItemDefaults.colors(
            containerColor = if (nowPlaying != null) MaterialTheme.colorScheme.secondaryContainer else ListItemDefaults.containerColor,
        ),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pinned = local?.pinned == true && local.state == DownloadState.DONE
                if (pinned) {
                    Icon(Icons.Filled.PushPin, contentDescription = stringResource(R.string.episode_list_pinned), Modifier.size(MARK_SIZE))
                    Spacer(Modifier.width(MARK_GAP))
                }
                when {
                    nowPlaying != null && nowPlaying.isPlaying -> {
                        Icon(Icons.Filled.GraphicEq, contentDescription = stringResource(R.string.episode_list_now_playing_playing), Modifier.size(MARK_SIZE), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(MARK_GAP))
                    }
                    nowPlaying != null -> {
                        Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.episode_list_now_playing_paused), Modifier.size(MARK_SIZE), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(MARK_GAP))
                    }
                    !pinned -> Spacer(Modifier.width(MARK_SIZE + MARK_GAP)) // 印が無い行の枠
                }
                Text(episode.episode.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        supportingContent = {
            Column(Modifier.padding(start = MARK_SIZE + MARK_GAP)) {
                Text(item.toSupportingText().resolve(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                // 尺が分からない回（runtime 0）にはバーを出さない（各回一覧と同じ）
                if (episode.isPlayable && resume != null && runtime > Duration.ZERO) {
                    LinearProgressIndicator(
                        progress = { (resume / runtime).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onTogglePlayed) {
                if (played) {
                    Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.episode_list_played_tap))
                } else {
                    Icon(Icons.Outlined.Circle, contentDescription = stringResource(R.string.episode_list_unplayed_tap))
                }
            }
        },
    )
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

/**
 * 長押しのシート。各回一覧の `EpisodeActionsSheet` と同じ中身（固定を外す・ファイルの削除・詳細）。
 * 「続きから」の回は必ず手元にあるので「ダウンロード」は出ない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContinueListeningActionsSheet(
    item: ContinueListeningViewModel.Item,
    onDismiss: () -> Unit,
    onUnpin: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
) {
    val episode = item.episode
    val local = episode.localFile
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(episode.episode.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (local?.state == DownloadState.DONE && local.pinned && item.program?.syncEnabled == true) {
            SheetAction(Icons.Outlined.PushPin, stringResource(R.string.episode_list_action_unpin)) { onUnpin(); onDismiss() }
        }
        if (local != null) {
            val label = stringResource(if (episode.episode.serverItemId != null) R.string.episode_list_action_delete_file else R.string.episode_list_action_delete_episode)
            SheetAction(Icons.Filled.Delete, label) { onDelete(); onDismiss() }
        }
        SheetAction(Icons.Outlined.Info, stringResource(R.string.episode_list_action_details)) { onDetails() }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
    )
}
