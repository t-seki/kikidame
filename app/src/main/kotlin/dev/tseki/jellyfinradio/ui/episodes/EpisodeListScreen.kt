package dev.tseki.jellyfinradio.ui.episodes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.CircularProgressIndicator
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
    val snackbarHostState = remember { SnackbarHostState() }
    var sheetFor by remember { mutableStateOf<EpisodeId?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

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

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
    )
}
