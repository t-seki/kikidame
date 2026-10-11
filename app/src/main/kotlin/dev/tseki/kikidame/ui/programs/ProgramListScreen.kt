package dev.tseki.kikidame.ui.programs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import dev.tseki.kikidame.domain.ProgramListTab
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ProgramSummary
import dev.tseki.kikidame.ui.BackgroundSyncBar
import dev.tseki.kikidame.ui.resolve
import dev.tseki.kikidame.ui.player.MiniPlayer

/**
 * 番組の画面（根）。「続きから・よく聴く・番組一覧」の 3 つのタブ（#151）を、トップバーのすぐ下のタブとスワイプで切り替える。
 * 起動時は前回最後に開いていたタブを開く（端末に保存。初回は「続きから」）。読めるまでは何も出さない（一瞬）。
 */
@Composable
fun ProgramListScreen(
    onProgramClick: (ProgramId) -> Unit,
    onSettingsClick: () -> Unit,
    onNowPlayingClick: (EpisodeId) -> Unit,
    onEpisodeClick: (EpisodeId) -> Unit,
    onEpisodeDetails: (ProgramId, EpisodeId) -> Unit,
    viewModel: ProgramListViewModel = hiltViewModel(),
    continueViewModel: ContinueListeningViewModel = hiltViewModel(),
) {
    val initialTab by viewModel.initialTab.collectAsStateWithLifecycle()
    val tab = initialTab
    if (tab == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    ProgramListTabs(tab, onProgramClick, onSettingsClick, onNowPlayingClick, onEpisodeClick, onEpisodeDetails, viewModel, continueViewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgramListTabs(
    initialTab: ProgramListTab,
    onProgramClick: (ProgramId) -> Unit,
    onSettingsClick: () -> Unit,
    onNowPlayingClick: (EpisodeId) -> Unit,
    onEpisodeClick: (EpisodeId) -> Unit,
    onEpisodeDetails: (ProgramId, EpisodeId) -> Unit,
    viewModel: ProgramListViewModel,
    continueViewModel: ContinueListeningViewModel,
) {
    val filtered by viewModel.filtered.collectAsStateWithLifecycle()
    val canRefresh by viewModel.canRefresh.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val isSyncingInBackground by viewModel.isSyncingInBackground.collectAsStateWithLifecycle()
    val tagProgress by viewModel.tagProgress.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // 他のシートと同じく remember（プロセス死で開き直さない。絞り込みの状態も復元しないので）
    var showFilterSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it.resolve(context)) }
    }
    LaunchedEffect(Unit) {
        continueViewModel.messages.collect { snackbarHostState.showSnackbar(it.resolve(context)) }
    }
    // 「続きから」で再生済みにしたときだけ［元に戻す］を出す（各回一覧では出さない）。続けて再生済みにしたら、
    // 前のスナックバーを待たせずに新しい方へ差し替える（collectLatest が前の showSnackbar を取り消すと閉じる）
    val markedPlayedText = stringResource(R.string.program_list_marked_played)
    val undoText = stringResource(R.string.program_list_undo)
    LaunchedEffect(Unit) {
        continueViewModel.markedPlayed.collectLatest { episodeId ->
            val result = snackbarHostState.showSnackbar(markedPlayedText, actionLabel = undoText, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) continueViewModel.setPlayed(episodeId, false)
        }
    }

    val tabs = ProgramListTab.entries
    val pagerState = rememberPagerState(initialPage = tabs.indexOf(initialTab)) { tabs.size }
    val scope = rememberCoroutineScope()
    // スワイプの途中では書かず、止まったタブを覚える
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { viewModel.selectTab(tabs[it]) }
    }
    // 検索と配信元の絞り込みは「よく聴く」「番組一覧」に効き、タブをまたいで保つ。「続きから」には出さない
    val filterable = tabs[pagerState.currentPage] != ProgramListTab.CONTINUE_LISTENING
    // 一覧の位置はタブごとに持つ（ページが画面外で破棄されても残す）
    val continueListState = rememberLazyListState()
    val starredListState = rememberLazyListState()
    val allListState = rememberLazyListState()
    // 検索語か配信元が変わるたびに、絞り込みが効く 2 つの一覧を先頭へ（絞った結果は先頭から見たい。解除したときも先頭に戻す）。
    // 末尾の空白など絞り込みに効かない変化では動かさず、初回も動かさない（番組から戻ったときに復元された位置を潰さない）
    val filterKey = ProgramFilter.normalize(query) to filtered?.publisher
    var seenKey by remember { mutableStateOf(filterKey) }
    LaunchedEffect(filterKey) {
        if (filterKey != seenKey) {
            seenKey = filterKey
            starredListState.requestScrollToItem(0)
            allListState.requestScrollToItem(0)
        }
    }

    // 絞り込みの入口（#55）は番組が 1 つでもあれば出す。配信元の段は 2 種類以上のときだけ（1 種類なら絞る意味が無い）
    val publishers = filtered?.publishers.orEmpty()
    if (showFilterSheet && publishers.isNotEmpty() && filterable) {
        FilterSheet(
            query = query,
            onQueryChange = viewModel::setQuery,
            publishers = publishers.takeIf { it.size >= 2 }.orEmpty(),
            selected = filtered?.publisher,
            onSelectPublisher = viewModel::selectPublisher,
            onDismiss = { showFilterSheet = false },
        )
    }
    // 絞り込みが効いている間の戻るボタンは絞り込みを解除するだけ（番組の画面は根なので、そのままだとアプリを抜ける）。
    // シートが開いている間はシート自身が戻るで閉じるので、こちらは効かせない。「続きから」では絞り込みが見えないので効かせない
    BackHandler(enabled = filtered?.isFiltering == true && !showFilterSheet && filterable, onBack = viewModel::clearFilters)
    Scaffold(
        topBar = {
            // 検索（#44）と配信元（#45）の入口を 1 つの「絞り込み」に統合（#55）。効いている間は点を付け、
            // 何で絞っているかはタブの下のチップで示す
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.program_list_title)) },
                    actions = {
                        if (publishers.isNotEmpty() && filterable) {
                            IconButton(onClick = { showFilterSheet = true }) {
                                BadgedBox(badge = { if (filtered?.isFiltering == true) Badge() }) {
                                    Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.program_list_filter))
                                }
                            }
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.program_list_settings))
                        }
                    },
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
                BackgroundSyncBar(isSyncingInBackground, tagProgress)
                if (filterable) {
                    FilterChipsRow(
                        query = ProgramFilter.normalize(query),
                        publisher = filtered?.publisher,
                        onClearQuery = { viewModel.setQuery("") },
                        onClearPublisher = { viewModel.selectPublisher(null) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = { MiniPlayer(onClick = onNowPlayingClick) },
    ) { padding ->
        HorizontalPager(pagerState, Modifier.padding(padding).fillMaxSize()) { page ->
            // 引っ張って更新はどのタブでもライブラリ全体の同期（いままでの番組一覧と同じ）
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { if (canRefresh) viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when (tabs[page]) {
                    ProgramListTab.CONTINUE_LISTENING -> ContinueListeningPage(
                        viewModel = continueViewModel,
                        listState = continueListState,
                        onEpisodeClick = onEpisodeClick,
                        onEpisodeDetails = onEpisodeDetails,
                    )
                    ProgramListTab.STARRED -> {
                        val state = filtered
                        when {
                            state == null -> Box(Modifier.fillMaxSize())
                            !state.hasStarred -> PageMessage(stringResource(R.string.program_list_starred_empty))
                            state.starred.isEmpty() -> NoMatch(query, state.publisher)
                            else -> ProgramsList(state.starred, starredListState, onProgramClick, viewModel::setStarred)
                        }
                    }
                    ProgramListTab.ALL_PROGRAMS -> {
                        val state = filtered
                        when {
                            state == null -> Box(Modifier.fillMaxSize())
                            // 番組自体が無い（配信元も集まらない）なら、検索中でも「一致なし」ではなく空の案内
                            state.publishers.isEmpty() -> EmptyPrograms(canRefresh)
                            state.programs.isEmpty() -> NoMatch(query, state.publisher)
                            // 節に分けず全番組を 1 本に（#151。いままでの「その他」と同じ並び）。よく聴く番組は ★ で見分ける
                            else -> ProgramsList(state.programs, allListState, onProgramClick, viewModel::setStarred)
                        }
                    }
                }
            }
        }
    }
}

/** タブの見出し。 */
private val ProgramListTab.label: Int
    get() = when (this) {
        ProgramListTab.CONTINUE_LISTENING -> R.string.program_list_tab_continue
        ProgramListTab.STARRED -> R.string.program_list_tab_starred
        ProgramListTab.ALL_PROGRAMS -> R.string.program_list_tab_all
    }

@Composable
private fun ProgramsList(
    list: List<ProgramSummary>,
    listState: LazyListState,
    onProgramClick: (ProgramId) -> Unit,
    onSetStarred: (ProgramId, Boolean) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        programItems(list, onProgramClick, onSetStarred)
    }
}

private fun LazyListScope.programItems(
    list: List<ProgramSummary>,
    onProgramClick: (ProgramId) -> Unit,
    onSetStarred: (ProgramId, Boolean) -> Unit,
) {
    items(list, key = { it.program.id.value }) { summary ->
        ProgramRow(
            summary,
            onClick = { onProgramClick(summary.program.id) },
            onToggleStarred = { onSetStarred(summary.program.id, !summary.program.starred) },
        )
        HorizontalDivider()
    }
}
