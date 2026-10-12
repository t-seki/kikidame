package dev.tseki.kikidame.ui.episodes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.download.DownloadProgress
import dev.tseki.kikidame.ui.BackgroundSyncBar
import dev.tseki.kikidame.text.UiText
import dev.tseki.kikidame.ui.player.MiniPlayer
import dev.tseki.kikidame.ui.resolve
import dev.tseki.kikidame.text.toPublishedDateText
import dev.tseki.kikidame.ui.toPerformersText
import dev.tseki.kikidame.ui.toClockText

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
    var syncSheet by remember { mutableStateOf(SyncSheetState.Hidden) }

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it.resolve(context)) }
    }
    LaunchedEffect(Unit) {
        viewModel.programRemoved.collect { onBack() }
    }

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
                        IconButton(onClick = { syncSheet = SyncSheetState.Open }, enabled = program != null) {
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
                        supportingText = item.toSupportingText(waitingForNetwork),
                        onClick = { onEpisodeClick(item.episode.id) },
                        onLongClick = { sheetFor = item.episode.id },
                        trailing = {
                            EpisodeTrailingAction(
                                item = item,
                                progress = progress?.takeIf { it.episodeId == item.episode.id },
                                onTogglePlayed = { viewModel.setPlayed(item.episode.id, item.playback?.played != true) },
                                onDownload = { viewModel.download(item.episode.id) },
                                onCancel = { viewModel.cancel(item.episode.id) },
                                onRetry = { viewModel.retry(item.episode.id) },
                            )
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    ProgramSyncDialogs(
        program = program,
        localStorage = localStorage,
        pendingDisable = pendingDisable,
        state = syncSheet,
        onStateChange = { syncSheet = it },
        viewModel = viewModel,
    )

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

/**
 * 各回一覧の行の右端: 雲 → ダウンロード（= 固定）、待機／進捗 → キャンセル、警告 → 再試行、手元にある回 → 再生済み切替。
 */
@Composable
private fun EpisodeTrailingAction(
    item: EpisodeWithState,
    progress: DownloadProgress?,
    onTogglePlayed: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val played = item.playback?.played == true
    when (item.localFile?.state) {
        null -> IconButton(onClick = onDownload, enabled = item.episode.sourceItemId != null) {
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
        DownloadState.DONE -> PlayedToggleButton(played = played, onToggle = onTogglePlayed)
    }
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
