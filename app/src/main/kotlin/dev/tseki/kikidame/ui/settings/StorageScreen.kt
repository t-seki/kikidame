package dev.tseki.kikidame.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.LocalEpisode
import dev.tseki.kikidame.domain.ProgramStorage
import dev.tseki.kikidame.text.toPublishedDateText
import dev.tseki.kikidame.ui.resolve
import dev.tseki.kikidame.ui.toSizeText
import dev.tseki.kikidame.ui.toText
import kotlinx.coroutines.launch

/*
 * 手元のファイルの内訳（#335）。設定の「手元のファイル」の行から開く。
 * - 内訳の画面（[StorageScreen]）: 上に合計、「番組ごと」と「すべての回」のタブ。番組の行を押すと番組ごとの一覧へ
 * - 番組ごとの一覧（[StorageProgramScreen]）: その番組の手元の回だけ（公開日の新しい順）
 * 回の行は押すとチェックが付く・外れる（詳細・再生には移らない）。1 回以上選ぶと下に「N 回を消す（XX MB）」が出る。
 */

private enum class StorageTab(val label: Int) {
    PROGRAMS(R.string.storage_tab_programs),
    EPISODES(R.string.storage_tab_episodes),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    onBack: () -> Unit,
    onProgramClick: (ProgramStorage) -> Unit,
    returnedMessage: String?,
    onReturnedMessageShown: () -> Unit,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val programs by viewModel.programs.collectAsStateWithLifecycle()
    val episodes by viewModel.visibleEpisodes.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val selection = rememberSelectionState(viewModel)
    val tabs = StorageTab.entries
    val pagerState = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    // 番組ごとの一覧で最後の回を消して戻ってきたときの文言（あちらの画面では出す前に閉じるので、ここで出す）
    LaunchedEffect(returnedMessage) {
        returnedMessage?.let {
            onReturnedMessageShown()
            selection.snackbarHostState.showSnackbar(it)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_storage)) },
                    navigationIcon = { BackButton(onBack) },
                )
                // 設定の行と同じ合計
                Text(
                    usage?.toText()?.resolve() ?: "-",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(selection.snackbarHostState) },
        // 消すボタンは選べるタブ（すべての回）にいるときだけ
        bottomBar = { if (tabs[pagerState.currentPage] == StorageTab.EPISODES) DeleteBar(selection) },
    ) { padding ->
        HorizontalPager(pagerState, Modifier.padding(padding).fillMaxSize()) { page ->
            when (tabs[page]) {
                StorageTab.PROGRAMS -> ProgramStorageList(programs, onProgramClick)
                StorageTab.EPISODES -> Column(Modifier.fillMaxSize()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        FilterChip(
                            selected = filter.pinned,
                            onClick = { viewModel.setFilter(filter.copy(pinned = !filter.pinned)) },
                            label = { Text(stringResource(R.string.storage_filter_pinned)) },
                        )
                        FilterChip(
                            selected = filter.played,
                            onClick = { viewModel.setFilter(filter.copy(played = !filter.played)) },
                            label = { Text(stringResource(R.string.storage_filter_played)) },
                        )
                    }
                    LocalEpisodeList(episodes, selection, showProgramName = true)
                }
            }
        }
    }
    DeleteConfirmDialog(selection)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageProgramScreen(
    onBack: () -> Unit,
    onEmptied: (message: String?) -> Unit,
    viewModel: StorageProgramViewModel = hiltViewModel(),
) {
    val episodes by viewModel.visibleEpisodes.collectAsStateWithLifecycle()
    val isDeleting by viewModel.isDeleting.collectAsStateWithLifecycle()
    val selection = rememberSelectionState(viewModel, showMessages = episodes?.isEmpty() != true)
    // 回を消して手元の回が 0 になったら内訳の画面に戻る。消した後の文言ができるまで待ち、内訳の画面に渡して出してもらう
    val context = LocalContext.current
    val emptied = episodes?.isEmpty() == true && !isDeleting
    LaunchedEffect(emptied) {
        if (emptied) {
            val message = viewModel.message.value?.resolve(context)
            viewModel.consumeMessage()
            onEmptied(message)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.programName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(selection.snackbarHostState) },
        bottomBar = { DeleteBar(selection) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LocalEpisodeList(episodes, selection, showProgramName = false)
        }
    }
    DeleteConfirmDialog(selection)
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
}

/** 選択と削除の見た目に要るもの。2 つの画面で同じ形にする。 */
private class SelectionUiState(
    val selected: Set<EpisodeId>,
    val listening: EpisodeId?,
    val selection: StorageSelection,
    val confirm: StorageSelection?,
    val isSyncing: Boolean,
    val snackbarHostState: SnackbarHostState,
    val viewModel: StorageSelectionViewModel,
)

@Composable
private fun rememberSelectionState(viewModel: StorageSelectionViewModel, showMessages: Boolean = true): SelectionUiState {
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val listening by viewModel.listening.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val confirm by viewModel.confirm.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(message, showMessages) {
        if (showMessages) message?.let {
            snackbarHostState.showSnackbar(it.resolve(context))
            viewModel.consumeMessage()
        }
    }
    return SelectionUiState(selected, listening, selection, confirm, isSyncing, snackbarHostState, viewModel)
}

/** 番組ごとの行。「N 回・XX MB／固定 a · ほか再生済み b · ほか未再生 c」。押すと番組ごとの一覧へ。 */
@Composable
private fun ProgramStorageList(programs: List<ProgramStorage>?, onProgramClick: (ProgramStorage) -> Unit) {
    programs ?: return
    if (programs.isEmpty()) {
        EmptyMessage()
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(programs, key = { it.programId.value }) { program ->
            ListItem(
                modifier = Modifier.clickable { onProgramClick(program) },
                headlineContent = { Text(program.programName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        pluralStringResource(
                            R.plurals.storage_program_summary,
                            program.episodeCount,
                            program.episodeCount,
                            program.totalBytes.toSizeText(),
                            program.pinnedCount,
                            program.playedUnpinnedCount,
                            program.unplayedUnpinnedCount,
                        ),
                    )
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun LocalEpisodeList(episodes: List<LocalEpisode>?, state: SelectionUiState, showProgramName: Boolean) {
    episodes ?: return
    if (episodes.isEmpty()) {
        EmptyMessage()
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(episodes, key = { it.episodeId.value }) { episode ->
            val listening = episode.episodeId == state.listening
            LocalEpisodeRow(
                episode = episode,
                checked = !listening && episode.episodeId in state.selected,
                listening = listening,
                showProgramName = showProgramName,
                onToggle = { state.viewModel.toggle(episode.episodeId) },
            )
        }
    }
}

@Composable
private fun EmptyMessage() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.storage_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** タイトル前の固定の印の大きさと、印とタイトルの間隔（各回一覧の行と同じ）。 */
private val MARK_SIZE = 14.dp
private val MARK_GAP = 4.dp

/**
 * 回の行。左にチェックボックス、タイトル（1 行。前に固定の印）、補足の行 `番組名 · 公開日 · 容量`（番組名は「すべての回」だけ）と、
 * 再生済み・取得元に無い（消すと戻せない）の印。聴いている回は各回一覧と同じ背景にし、選べなくする。
 */
@Composable
private fun LocalEpisodeRow(
    episode: LocalEpisode,
    checked: Boolean,
    listening: Boolean,
    showProgramName: Boolean,
    onToggle: () -> Unit,
) {
    val played = stringResource(R.string.common_played)
    val noSource = stringResource(R.string.storage_no_source)
    val supporting = listOfNotNull(
        episode.programName.takeIf { showProgramName },
        episode.publishedAt.toPublishedDateText(),
        episode.sizeBytes.toSizeText(),
        played.takeIf { episode.played },
        noSource.takeIf { !episode.hasSourceItemId },
    ).joinToString(" · ")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = !listening, role = Role.Checkbox, onValueChange = { onToggle() }),
        color = if (listening) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 72.dp).padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = !listening, modifier = Modifier.padding(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (episode.pinned) {
                        Icon(Icons.Filled.PushPin, contentDescription = stringResource(R.string.episode_list_pinned), Modifier.size(MARK_SIZE))
                    } else {
                        Spacer(Modifier.size(MARK_SIZE))
                    }
                    Spacer(Modifier.width(MARK_GAP))
                    Text(episode.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = MARK_SIZE + MARK_GAP),
                )
            }
        }
    }
}

/** 1 回以上選ぶと出る「N 回を消す（XX MB）」。同期の間は無効にし、「同期の後に使えます」と出す（回を選ぶことはできる）。 */
@Composable
private fun DeleteBar(state: SelectionUiState) {
    val selection = state.selection
    if (selection.count == 0) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (state.isSyncing) {
                Text(
                    stringResource(R.string.storage_delete_disabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Button(
                onClick = state.viewModel::requestDelete,
                enabled = !state.isSyncing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(pluralStringResource(R.plurals.storage_delete_button, selection.count, selection.count, selection.totalBytes.toSizeText()))
            }
        }
    }
}

/**
 * 消す前の確認。件数と容量、取得元 ID の無い回の件数（0 回なら書かない）、同期対象の番組の回を含むときだけ一般の文を足す。
 * どの回が落とし直されるかの予測はしない。ボタンは赤の「消す」と「キャンセル」（#317 の確認と同じ形）。
 */
@Composable
private fun DeleteConfirmDialog(state: SelectionUiState) {
    val confirm = state.confirm ?: return
    val sentences = listOfNotNull(
        pluralStringResource(R.plurals.storage_delete_text, confirm.count, confirm.count, confirm.totalBytes.toSizeText()),
        confirm.unrecoverableCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.storage_delete_text_unrecoverable, it, it) },
        stringResource(R.string.storage_delete_text_synced).takeIf { confirm.hasSyncedProgram },
    )
    AlertDialog(
        onDismissRequest = state.viewModel::cancelDelete,
        title = { Text(stringResource(R.string.storage_delete_title)) },
        text = { Text(sentences.joinToString(stringResource(R.string.storage_sentence_separator))) },
        confirmButton = {
            TextButton(onClick = state.viewModel::confirmDelete) {
                Text(stringResource(R.string.storage_delete_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = state.viewModel::cancelDelete) { Text(stringResource(R.string.common_cancel)) } },
    )
}
