package dev.tseki.kikidame.ui.episodes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LocalStorageUsage
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.download.DownloadProgress
import dev.tseki.kikidame.playback.NowPlayingState
import dev.tseki.kikidame.ui.BackgroundSyncBar
import dev.tseki.kikidame.ui.UiText
import dev.tseki.kikidame.ui.player.MiniPlayer
import dev.tseki.kikidame.ui.resolve
import dev.tseki.kikidame.ui.toPublishedDateText
import dev.tseki.kikidame.ui.toPerformersText
import dev.tseki.kikidame.ui.toText
import dev.tseki.kikidame.ui.toClockText
import kotlin.time.Duration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeListScreen(
    onEpisodeClick: (EpisodeId) -> Unit,
    onNowPlayingClick: (EpisodeId) -> Unit,
    onEpisodeDetails: (ProgramId, EpisodeId) -> Unit,
    onBack: () -> Unit,
    viewModel: EpisodeListViewModel = hiltViewModel(),
) {
    val program by viewModel.program.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val localStorage by viewModel.localStorage.collectAsStateWithLifecycle()
    val canRefresh by viewModel.canRefresh.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val isSyncingInBackground by viewModel.isSyncingInBackground.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val waitingForNetwork by viewModel.waitingForNetwork.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val pendingDisable by viewModel.pendingDisable.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var sheetFor by remember { mutableStateOf<EpisodeId?>(null) }
    var showSyncSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it.resolve(context)) }
    }
    LaunchedEffect(Unit) {
        viewModel.programRemoved.collect { onBack() }
    }
    var confirmRemoveProgram by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(program?.name ?: "")
                            program?.publisherName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.setStarred(program?.starred != true) }, enabled = program != null) {
                            if (program?.starred == true) {
                                Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.program_list_star_remove), tint = MaterialTheme.colorScheme.primary)
                            } else {
                                Icon(Icons.Outlined.StarOutline, contentDescription = stringResource(R.string.program_list_star_add))
                            }
                        }
                        IconButton(onClick = { showSyncSheet = true }, enabled = program != null) {
                            // filled と outlined の Sync は形がほぼ同じなので、OFF は斜線入りで区別する
                            if (program?.syncEnabled == true) {
                                Icon(Icons.Filled.Sync, contentDescription = stringResource(R.string.episode_list_sync_settings_on), tint = MaterialTheme.colorScheme.primary)
                            } else {
                                Icon(Icons.Filled.SyncDisabled, contentDescription = stringResource(R.string.episode_list_sync_settings_off))
                            }
                        }
                    },
                )
                BackgroundSyncBar(isSyncingInBackground)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = { MiniPlayer(onClick = onNowPlayingClick) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { if (canRefresh) viewModel.refresh() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(episodes.orEmpty(), key = { it.episode.id.value }) { item ->
                    EpisodeRow(
                        item = item,
                        nowPlaying = nowPlaying?.takeIf { it.episodeId == item.episode.id },
                        progress = progress?.takeIf { it.episodeId == item.episode.id },
                        waitingForNetwork = waitingForNetwork,
                        onClick = { onEpisodeClick(item.episode.id) },
                        onLongClick = { sheetFor = item.episode.id },
                        onTogglePlayed = { viewModel.setPlayed(item.episode.id, item.playback?.played != true) },
                        onDownload = { viewModel.download(item.episode.id) },
                        onCancel = { viewModel.cancel(item.episode.id) },
                        onRetry = { viewModel.retry(item.episode.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    val currentProgram = program
    if (showSyncSheet && currentProgram != null) {
        ProgramSyncSheet(
            program = currentProgram,
            localStorage = localStorage,
            onDismiss = { showSyncSheet = false },
            onSetEnabled = viewModel::setSyncEnabled,
            onKeepLatest = viewModel::setKeepLatest,
            onDeleteAfterPlayed = viewModel::setDeleteAfterPlayed,
            onSyncNow = { viewModel.syncNow(); showSyncSheet = false },
            onRemoveProgram = { showSyncSheet = false; confirmRemoveProgram = true },
        )
    }
    if (confirmRemoveProgram && currentProgram != null) {
        AlertDialog(
            onDismissRequest = { confirmRemoveProgram = false },
            title = { Text(stringResource(R.string.episode_list_remove_program_title)) },
            text = { Text(stringResource(R.string.episode_list_remove_program_text, currentProgram.name)) },
            confirmButton = {
                TextButton(onClick = { confirmRemoveProgram = false; viewModel.removeProgram() }) {
                    Text(stringResource(R.string.episode_list_remove_program_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveProgram = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    pendingDisable?.let { count ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDisableSync,
            title = { Text(stringResource(R.string.episode_list_disable_sync_title)) },
            text = { Text(pluralStringResource(R.plurals.episode_list_disable_sync_text, count, count)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDisableSync) { Text(stringResource(R.string.episode_list_disable_sync_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelDisableSync) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    val target = sheetFor?.let { id -> episodes?.firstOrNull { it.episode.id == id } }
    if (target != null) {
        EpisodeActionsSheet(
            item = target,
            syncEnabled = program?.syncEnabled == true,
            onDismiss = { sheetFor = null },
            onDownload = { viewModel.download(target.episode.id) },
            onUnpin = { viewModel.unpin(target.episode.id) },
            onDelete = { viewModel.deleteLocal(target.episode.id) },
            // 詳細（#43）は画面へ。シートは閉じてから遷移する
            onDetails = { sheetFor = null; onEpisodeDetails(target.episode.programId, target.episode.id) },
        )
    }
}

/** タイトル前の印の大きさと、印とタイトルの間隔。印の無い行の枠と補足行の字下げにも使う。 */
private val MARK_SIZE = 14.dp
private val MARK_GAP = 4.dp
/**
 * 右端のアイコンは状態を表し、タップで最も自然な 1 操作をする:
 * 雲 → ダウンロード（= 固定）、待機／進捗 → キャンセル、警告 → 再試行、手元にある回 → 再生済み切替。
 * 残りの操作は長押しのボトムシート。手元に無い回はタップで再生画面へ行かない。
 * タイトルの前に印を出す: 固定（ダウンロード済みで固定された回）のピンと、聴いている回（[nowPlaying] がこの回。背景も変える）の
 * 再生中／一時停止。両方あれば並べる。印の無い行にも同じ幅を確保し、タイトル（1 行）と補足行の開始位置を揃える。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    item: EpisodeWithState,
    nowPlaying: NowPlayingState?,
    progress: DownloadProgress?,
    waitingForNetwork: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTogglePlayed: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val played = item.playback?.played == true
    val resume = item.resumePosition
    val runtime = item.episode.runtime
    val playable = item.isPlayable
    val local = item.localFile
    val state = local?.state
    ListItem(
        modifier = Modifier
            .combinedClickable(onClick = { if (playable) onClick() }, onLongClick = onLongClick)
            .alpha(if (playable || state != null) 1f else 0.5f),
        colors = ListItemDefaults.colors(
            containerColor = if (nowPlaying != null) MaterialTheme.colorScheme.secondaryContainer else ListItemDefaults.containerColor,
        ),
        headlineContent = {
            // 印（固定・再生中／一時停止）はタイトルの前に置くが、印の無い行にも同じ幅の枠を確保してタイトルの開始位置を揃える（#56）。
            // 固定と聴いている回は独立した状態なので両方出す（両方ある行だけ 1 つ分右にずれるが、背景色で目立つ 1 行なので許容）
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pinned = local?.pinned == true && state == DownloadState.DONE
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
                Text(item.episode.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        supportingContent = {
            // 補足行もタイトルと同じ位置から始める（枠＋間隔ぶんを空ける）
            Column(Modifier.padding(start = MARK_SIZE + MARK_GAP)) {
                Text(item.toSupportingText(waitingForNetwork).resolve())
                if (playable && resume != null && runtime > Duration.ZERO) {
                    LinearProgressIndicator(
                        progress = { (resume / runtime).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        },
        trailingContent = {
            when (state) {
                null -> IconButton(onClick = onDownload, enabled = item.episode.serverItemId != null) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = stringResource(R.string.episode_list_download))
                }
                DownloadState.PENDING -> IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Schedule, contentDescription = stringResource(R.string.episode_list_pending_tap_cancel))
                }
                DownloadState.RUNNING -> IconButton(onClick = onCancel) {
                    val fraction = progress?.fraction
                    if (fraction != null && fraction > 0f) {
                        CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    }
                }
                DownloadState.FAILED -> IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = stringResource(R.string.episode_list_failed_tap_retry), tint = MaterialTheme.colorScheme.error)
                }
                DownloadState.DONE -> IconButton(onClick = onTogglePlayed) {
                    if (played) {
                        Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.episode_list_played_tap))
                    } else {
                        Icon(Icons.Outlined.Circle, contentDescription = stringResource(R.string.episode_list_unplayed_tap))
                    }
                }
            }
        },
    )
}

/**
 * 行の補足: `公開日 · 出演者 · 尺 · 状態`。出演者（#70）は無い回では省き、状態はダウンロードの待機／進行／失敗のときだけ付く。
 * 録音側が各回のタイトルを `YYYY-MM-DD` と付けるので、タイトルが公開日と同じ文字列なら同じ日付が 2 段に並んで
 * 冗長になる。そのときだけ公開日を省いて `出演者 · 尺 · 状態` にする（#66）。判定は完全一致で、表記ゆれ（`2026/08/02`）や
 * `(1)` 付きは一致とみなさない（そういう回では両方の値に意味がある）。
 */
internal fun EpisodeWithState.toSupportingText(waitingForNetwork: Boolean): UiText {
    val published = episode.publishedAt.toPublishedDateText().takeIf { it != episode.title }?.let(UiText::Plain)
    val status = when (localFile?.state) {
        DownloadState.PENDING -> UiText.Res(if (waitingForNetwork) R.string.episode_list_status_waiting_wifi else R.string.common_download_pending)
        DownloadState.RUNNING -> UiText.Res(R.string.common_download_running)
        DownloadState.FAILED -> UiText.Res(R.string.episode_list_failed_tap_retry)
        else -> null
    }
    val parts = listOfNotNull(published, episode.performers.toPerformersText(), UiText.Plain(episode.runtime.toClockText()), status)
    return UiText.Joined(parts, UiText.Plain(" · "))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeActionsSheet(
    item: EpisodeWithState,
    syncEnabled: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onUnpin: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
) {
    val local = item.localFile
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(item.episode.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (local == null && item.episode.serverItemId != null) {
            SheetAction(Icons.Filled.Download, stringResource(R.string.episode_list_action_download)) { onDownload(); onDismiss() }
        }
        if (local?.state == DownloadState.DONE && local.pinned && syncEnabled) {
            SheetAction(Icons.Outlined.PushPin, stringResource(R.string.episode_list_action_unpin)) { onUnpin(); onDismiss() }
        }
        if (local != null) {
            val label = stringResource(if (item.episode.serverItemId != null) R.string.episode_list_action_delete_file else R.string.episode_list_action_delete_episode)
            SheetAction(Icons.Filled.Delete, label) { onDelete(); onDismiss() }
        }
        SheetAction(Icons.Outlined.Info, stringResource(R.string.episode_list_action_details)) { onDetails() }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

/**
 * 同期対象と保持ルールの編集。保存するだけで、適用は次の同期（保存した瞬間には何も消えない）。
 * サーバ ID の無い番組は同期できないのでスイッチを無効にする。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProgramSyncSheet(
    program: Program,
    localStorage: LocalStorageUsage?,
    onDismiss: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onKeepLatest: (Int?) -> Unit,
    onDeleteAfterPlayed: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onRemoveProgram: () -> Unit,
) {
    // 消失した番組（サーバの一覧に無く、突合でも結び直せなかった）とサーバ ID の無い番組は同期できない
    val canSync = program.serverItemId != null && !program.isGone
    val enabled = program.syncEnabled
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(program.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        // この番組の手元のファイル（#42）。保持ルールを触る前に目に入る位置に
        localStorage?.let {
            Text(
                if (it.episodeCount > 0) stringResource(R.string.episode_list_storage, it.toText().resolve()) else stringResource(R.string.episode_list_storage_empty),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        ListItem(
            modifier = Modifier.combinedClickable(enabled = canSync) { onSetEnabled(!enabled) },
            headlineContent = { Text(stringResource(R.string.episode_list_sync_this_program)) },
            supportingContent = {
                Text(
                    when {
                        program.isGone -> stringResource(R.string.episode_list_sync_gone, program.goneSince?.toPublishedDateText().orEmpty())
                        !canSync -> stringResource(R.string.episode_list_sync_unavailable)
                        else -> stringResource(R.string.episode_list_sync_description)
                    },
                )
            },
            trailingContent = { Switch(checked = enabled && canSync, onCheckedChange = onSetEnabled, enabled = canSync) },
        )
        ListItem(
            modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
            headlineContent = { Text(stringResource(R.string.episode_list_keep_latest)) },
            supportingContent = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    for (choice in EpisodeListViewModel.KEEP_LATEST_CHOICES) {
                        FilterChip(
                            selected = program.retentionRule.keepLatest == choice,
                            onClick = { onKeepLatest(choice) },
                            enabled = enabled,
                            label = { Text(choice?.let { pluralStringResource(R.plurals.episode_list_keep_latest_choice, it, it) } ?: stringResource(R.string.episode_list_keep_unlimited)) },
                        )
                    }
                }
            },
        )
        ListItem(
            modifier = Modifier
                .alpha(if (enabled) 1f else 0.5f)
                .combinedClickable(enabled = enabled) { onDeleteAfterPlayed(!program.retentionRule.deleteAfterPlayed) },
            headlineContent = { Text(stringResource(R.string.episode_list_delete_after_played)) },
            supportingContent = { Text(stringResource(R.string.episode_list_delete_after_played_description)) },
            trailingContent = {
                Switch(checked = program.retentionRule.deleteAfterPlayed, onCheckedChange = onDeleteAfterPlayed, enabled = enabled)
            },
        )
        if (canSync) {
            Button(onClick = onSyncNow, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth()) {
                Text(stringResource(R.string.episode_list_sync_now))
            }
        } else {
            // サーバに在る番組を消しても次の同期で戻ってくる（再生位置だけ失う）ので、消せるのはサーバに無い番組だけ
            SheetAction(Icons.Filled.Delete, stringResource(R.string.episode_list_remove_program)) { onRemoveProgram() }
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
    )
}
