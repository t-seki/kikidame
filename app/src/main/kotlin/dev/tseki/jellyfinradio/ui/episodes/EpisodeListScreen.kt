package dev.tseki.jellyfinradio.ui.episodes

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
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.PushPin
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.jellyfinradio.domain.DownloadState
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.EpisodeWithState
import dev.tseki.jellyfinradio.domain.Program
import dev.tseki.jellyfinradio.download.DownloadProgress
import dev.tseki.jellyfinradio.ui.toAiredDateText
import dev.tseki.jellyfinradio.ui.toClockText
import kotlin.time.Duration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeListScreen(
    onEpisodeClick: (EpisodeId) -> Unit,
    onBack: () -> Unit,
    viewModel: EpisodeListViewModel = hiltViewModel(),
) {
    val program by viewModel.program.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val canRefresh by viewModel.canRefresh.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val waitingForNetwork by viewModel.waitingForNetwork.collectAsStateWithLifecycle()
    val pendingDisable by viewModel.pendingDisable.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var sheetFor by remember { mutableStateOf<EpisodeId?>(null) }
    var showSyncSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        viewModel.programRemoved.collect { onBack() }
    }
    var confirmRemoveProgram by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(program?.name ?: "")
                        program?.stationName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { showSyncSheet = true }, enabled = program != null) {
                        // filled と outlined の Sync は形がほぼ同じなので、OFF は斜線入りで区別する
                        if (program?.syncEnabled == true) {
                            Icon(Icons.Filled.Sync, contentDescription = "同期の設定（同期対象）", tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Filled.SyncDisabled, contentDescription = "同期の設定（同期していない）")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            title = { Text("この番組を手元から消しますか？") },
            text = { Text("「${currentProgram.name}」の各回・ファイル・再生位置をすべて消します。この操作は取り消せません。") },
            confirmButton = {
                TextButton(onClick = { confirmRemoveProgram = false; viewModel.removeProgram() }) {
                    Text("手元から消す", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveProgram = false }) { Text("キャンセル") } },
        )
    }
    pendingDisable?.let { count ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDisableSync,
            title = { Text("同期をやめますか？") },
            text = { Text("固定されていない $count 回のファイルが次の同期で削除されます。残したい回は先に固定してください。") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDisableSync) { Text("同期をやめる", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelDisableSync) { Text("キャンセル") } },
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
            onTogglePlayed = { viewModel.setPlayed(target.episode.id, target.playback?.played != true) },
        )
    }
}

/**
 * 右端のアイコンは状態を表し、タップで最も自然な 1 操作をする:
 * 雲 → ダウンロード（= 固定）、待機／進捗 → キャンセル、警告 → 再試行、手元にある回 → 再生済み切替。
 * 残りの操作は長押しのボトムシート。手元に無い回はタップで再生画面へ行かない。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    item: EpisodeWithState,
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
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (local?.pinned == true && state == DownloadState.DONE) {
                    Icon(Icons.Filled.PushPin, contentDescription = "固定", Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(item.episode.title)
            }
        },
        supportingContent = {
            Column {
                val status = when (state) {
                    DownloadState.PENDING -> if (waitingForNetwork) " · Wi-Fi 待ち" else " · 待機中"
                    DownloadState.RUNNING -> " · ダウンロード中"
                    DownloadState.FAILED -> " · 失敗（タップで再試行）"
                    else -> ""
                }
                Text("${item.episode.airedAt.toAiredDateText()} · ${runtime.toClockText()}$status")
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
                    Icon(Icons.Outlined.CloudDownload, contentDescription = "ダウンロード")
                }
                DownloadState.PENDING -> IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Schedule, contentDescription = "待機中（タップでキャンセル）")
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
                    Icon(Icons.Filled.ErrorOutline, contentDescription = "失敗（タップで再試行）", tint = MaterialTheme.colorScheme.error)
                }
                DownloadState.DONE -> IconButton(onClick = onTogglePlayed) {
                    if (played) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "再生済み（タップで未再生に）")
                    } else {
                        Icon(Icons.Outlined.Circle, contentDescription = "未再生（タップで再生済みに）")
                    }
                }
            }
        },
    )
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
    onTogglePlayed: () -> Unit,
) {
    val local = item.localFile
    val played = item.playback?.played == true
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(item.episode.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (local == null && item.episode.serverItemId != null) {
            SheetAction(Icons.Filled.Download, "ダウンロード（固定）") { onDownload(); onDismiss() }
        }
        if (local?.state == DownloadState.DONE && local.pinned && syncEnabled) {
            SheetAction(Icons.Outlined.PushPin, "固定を外す（保持ルールの対象にする）") { onUnpin(); onDismiss() }
        }
        if (local != null) {
            val label = if (item.episode.serverItemId != null) "ファイルを削除（再生位置は残る）" else "この回を消す（サーバに無いため戻せない）"
            SheetAction(Icons.Filled.Delete, label) { onDelete(); onDismiss() }
        }
        SheetAction(
            if (played) Icons.Outlined.Circle else Icons.Filled.CheckCircle,
            if (played) "未再生にする" else "再生済みにする",
        ) { onTogglePlayed(); onDismiss() }
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(program.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        ListItem(
            modifier = Modifier.combinedClickable(enabled = canSync) { onSetEnabled(!enabled) },
            headlineContent = { Text("この番組を同期する") },
            supportingContent = {
                Text(
                    when {
                        program.isGone -> "サーバ上で見つかりません（${program.goneSince?.toAiredDateText()} から）。同期は止まっています。手元の回はそのまま聴けます"
                        !canSync -> "サーバ上で見つかっていないため同期できません"
                        else -> "保持ルールに従って自動でダウンロードし、外れた回を削除します。固定した回は残ります"
                    },
                )
            },
            trailingContent = { Switch(checked = enabled && canSync, onCheckedChange = onSetEnabled, enabled = canSync) },
        )
        ListItem(
            modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
            headlineContent = { Text("最新 N 回まで保持") },
            supportingContent = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    for (choice in EpisodeListViewModel.KEEP_LATEST_CHOICES) {
                        FilterChip(
                            selected = program.retentionRule.keepLatest == choice,
                            onClick = { onKeepLatest(choice) },
                            enabled = enabled,
                            label = { Text(choice?.let { "$it 回" } ?: "上限なし") },
                        )
                    }
                }
            },
        )
        ListItem(
            modifier = Modifier
                .alpha(if (enabled) 1f else 0.5f)
                .combinedClickable(enabled = enabled) { onDeleteAfterPlayed(!program.retentionRule.deleteAfterPlayed) },
            headlineContent = { Text("再生済みなら削除") },
            supportingContent = { Text("聴き終えた回を次の同期で手元から消します") },
            trailingContent = {
                Switch(checked = program.retentionRule.deleteAfterPlayed, onCheckedChange = onDeleteAfterPlayed, enabled = enabled)
            },
        )
        if (canSync) {
            Button(onClick = onSyncNow, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth()) {
                Text("この番組を今すぐ同期")
            }
        } else {
            // サーバに在る番組を消しても次の同期で戻ってくる（再生位置だけ失う）ので、消せるのはサーバに無い番組だけ
            SheetAction(Icons.Filled.Delete, "この番組を手元から消す") { onRemoveProgram() }
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
