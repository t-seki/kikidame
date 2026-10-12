package dev.tseki.kikidame.ui.episodes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.Episode
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LocalFile
import dev.tseki.kikidame.domain.PlaybackState
import dev.tseki.kikidame.domain.LocalStorageUsage
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.download.DownloadProgress
import dev.tseki.kikidame.ui.BackgroundSyncBar
import dev.tseki.kikidame.text.UiText
import dev.tseki.kikidame.playback.NowPlayingState
import dev.tseki.kikidame.ui.player.MiniPlayer
import dev.tseki.kikidame.ui.theme.KikidameTheme
import dev.tseki.kikidame.ui.resolve
import dev.tseki.kikidame.text.toPublishedDateText
import dev.tseki.kikidame.ui.toPerformersText
import dev.tseki.kikidame.ui.toClockText

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

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it.resolve(context)) }
    }
    LaunchedEffect(Unit) {
        viewModel.programRemoved.collect { onBack() }
    }

    EpisodeListContent(
        program = program,
        episodes = episodes,
        localStorage = localStorage,
        canRefresh = canRefresh,
        isRefreshing = isRefreshing,
        isSyncingInBackground = isSyncingInBackground,
        progress = progress,
        waitingForNetwork = waitingForNetwork,
        nowPlaying = nowPlaying,
        pendingDisable = pendingDisable,
        snackbarHostState = snackbarHostState,
        // MiniPlayer は中で hiltViewModel() を呼ぶので、Content の外から渡す
        bottomBar = { MiniPlayer(onClick = onNowPlayingClick) },
        actions = EpisodeListActions(
            setStarred = viewModel::setStarred,
            refresh = viewModel::refresh,
            setPlayed = viewModel::setPlayed,
            download = viewModel::download,
            cancel = viewModel::cancel,
            retry = viewModel::retry,
            unpin = viewModel::unpin,
            deleteLocal = viewModel::deleteLocal,
            setSyncEnabled = viewModel::setSyncEnabled,
            setKeepLatest = viewModel::setKeepLatest,
            setDeleteAfterPlayed = viewModel::setDeleteAfterPlayed,
            syncNow = viewModel::syncNow,
            removeProgram = viewModel::removeProgram,
            cancelDisableSync = viewModel::cancelDisableSync,
            confirmDisableSync = viewModel::confirmDisableSync,
        ),
        onEpisodeClick = onEpisodeClick,
        onEpisodeDetails = onEpisodeDetails,
        onBack = onBack,
    )
}

/** [EpisodeListContent] が呼ぶ ViewModel の操作。ラムダだけで、ViewModel は持たない。 */
internal class EpisodeListActions(
    val setStarred: (Boolean) -> Unit,
    val refresh: () -> Unit,
    val setPlayed: (EpisodeId, Boolean) -> Unit,
    val download: (EpisodeId) -> Unit,
    val cancel: (EpisodeId) -> Unit,
    val retry: (EpisodeId) -> Unit,
    val unpin: (EpisodeId) -> Unit,
    val deleteLocal: (EpisodeId) -> Unit,
    val setSyncEnabled: (Boolean) -> Unit,
    val setKeepLatest: (Int?) -> Unit,
    val setDeleteAfterPlayed: (Boolean) -> Unit,
    val syncNow: () -> Unit,
    val removeProgram: () -> Unit,
    val cancelDisableSync: () -> Unit,
    val confirmDisableSync: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeListContent(
    program: Program?,
    episodes: List<EpisodeWithState>?,
    localStorage: LocalStorageUsage?,
    canRefresh: Boolean,
    isRefreshing: Boolean,
    isSyncingInBackground: Boolean,
    progress: DownloadProgress?,
    waitingForNetwork: Boolean,
    nowPlaying: NowPlayingState?,
    pendingDisable: Int?,
    snackbarHostState: SnackbarHostState,
    bottomBar: @Composable () -> Unit,
    actions: EpisodeListActions,
    onEpisodeClick: (EpisodeId) -> Unit,
    onEpisodeDetails: (ProgramId, EpisodeId) -> Unit,
    onBack: () -> Unit,
) {
    var sheetFor by remember { mutableStateOf<EpisodeId?>(null) }
    var syncSheet by remember { mutableStateOf(SyncSheetState.Hidden) }

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
                        IconButton(onClick = { actions.setStarred(program?.starred != true) }, enabled = program != null) {
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
        bottomBar = bottomBar,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { if (canRefresh) actions.refresh() },
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
                                onTogglePlayed = { actions.setPlayed(item.episode.id, item.playback?.played != true) },
                                onDownload = { actions.download(item.episode.id) },
                                onCancel = { actions.cancel(item.episode.id) },
                                onRetry = { actions.retry(item.episode.id) },
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
        actions = actions,
    )

    val target = sheetFor?.let { id -> episodes?.firstOrNull { it.episode.id == id } }
    if (target != null) {
        EpisodeActionsSheet(
            item = target,
            syncEnabled = program?.syncEnabled == true,
            onDismiss = { sheetFor = null },
            onDownload = { actions.download(target.episode.id) },
            onUnpin = { actions.unpin(target.episode.id) },
            onDelete = { actions.deleteLocal(target.episode.id) },
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

private val previewActions = EpisodeListActions(
    setStarred = {},
    refresh = {},
    setPlayed = { _, _ -> },
    download = {},
    cancel = {},
    retry = {},
    unpin = {},
    deleteLocal = {},
    setSyncEnabled = {},
    setKeepLatest = {},
    setDeleteAfterPlayed = {},
    syncNow = {},
    removeProgram = {},
    cancelDisableSync = {},
    confirmDisableSync = {},
)

private val previewProgram = Program(
    id = ProgramId(1),
    sourceItemId = null,
    name = "サンプル番組",
    publisherName = "サンプル放送",
    syncEnabled = true,
)

private fun previewEpisode(id: Long, title: String, state: DownloadState?, played: Boolean = false): EpisodeWithState = EpisodeWithState(
    episode = Episode(
        id = EpisodeId(id),
        sourceItemId = SourceItemId("source-$id"),
        programId = previewProgram.id,
        title = title,
        publishedAt = Instant.fromEpochSeconds(1_785_000_000L - id * 86_400),
        addedAt = null,
        runtime = 45.minutes,
        sizeBytes = 40_000_000,
        container = "mp3",
        performers = listOf("出演者A"),
    ),
    localFile = state?.let { LocalFile(EpisodeId(id), it, path = "/preview/$id.mp3", pinned = false) },
    playback = PlaybackState(EpisodeId(id), position = 10.minutes, played = played, updatedAt = Instant.fromEpochSeconds(0)),
)

@Preview(showBackground = true)
@Composable
private fun EpisodeListContentPreview() {
    KikidameTheme {
        EpisodeListContent(
            program = previewProgram,
            episodes = listOf(
                previewEpisode(1, "第 5 回", DownloadState.DONE),
                previewEpisode(2, "第 4 回", DownloadState.RUNNING),
                previewEpisode(3, "第 3 回", DownloadState.PENDING),
                previewEpisode(4, "第 2 回", null),
            ),
            localStorage = LocalStorageUsage(totalBytes = 40_000_000, episodeCount = 1),
            canRefresh = true,
            isRefreshing = false,
            isSyncingInBackground = false,
            progress = DownloadProgress(EpisodeId(2), 0.4f),
            waitingForNetwork = false,
            nowPlaying = null,
            pendingDisable = null,
            snackbarHostState = remember { SnackbarHostState() },
            bottomBar = {},
            actions = previewActions,
            onEpisodeClick = {},
            onEpisodeDetails = { _, _ -> },
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EpisodeListContentEmptyPreview() {
    KikidameTheme {
        EpisodeListContent(
            program = previewProgram,
            episodes = emptyList(),
            localStorage = LocalStorageUsage(totalBytes = 0, episodeCount = 0),
            canRefresh = true,
            isRefreshing = false,
            isSyncingInBackground = false,
            progress = null,
            waitingForNetwork = false,
            nowPlaying = null,
            pendingDisable = null,
            snackbarHostState = remember { SnackbarHostState() },
            bottomBar = {},
            actions = previewActions,
            onEpisodeClick = {},
            onEpisodeDetails = { _, _ -> },
            onBack = {},
        )
    }
}

/** 右端の操作の分岐を横に並べる: ダウンロード前・待機・進行中（割合なし・あり）・失敗・済み（未再生・再生済み）。 */
@Preview(showBackground = true)
@Composable
private fun EpisodeTrailingActionPreview() {
    KikidameTheme {
        Row {
            val branches = listOf(
                previewEpisode(1, "ダウンロード前", null) to null,
                previewEpisode(2, "待機", DownloadState.PENDING) to null,
                previewEpisode(3, "進行中（割合なし）", DownloadState.RUNNING) to null,
                previewEpisode(4, "進行中（割合あり）", DownloadState.RUNNING) to DownloadProgress(EpisodeId(4), 0.6f),
                previewEpisode(5, "失敗", DownloadState.FAILED) to null,
                previewEpisode(6, "済み（未再生）", DownloadState.DONE) to null,
                previewEpisode(7, "済み（再生済み）", DownloadState.DONE, played = true) to null,
            )
            for ((item, progress) in branches) {
                EpisodeTrailingAction(
                    item = item,
                    progress = progress,
                    onTogglePlayed = {},
                    onDownload = {},
                    onCancel = {},
                    onRetry = {},
                )
            }
        }
    }
}
