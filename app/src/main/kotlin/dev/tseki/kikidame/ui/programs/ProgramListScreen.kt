package dev.tseki.kikidame.ui.programs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import dev.tseki.kikidame.domain.ProgramListTab
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.InputChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ProgramSummary
import dev.tseki.kikidame.ui.BackgroundSyncBar
import dev.tseki.kikidame.ui.UiText
import dev.tseki.kikidame.ui.resolve
import dev.tseki.kikidame.ui.player.MiniPlayer
import dev.tseki.kikidame.ui.programs.ProgramFilter.Publisher
import dev.tseki.kikidame.ui.programs.ProgramFilter.PublisherKey
import dev.tseki.kikidame.domain.PublishedAt
import dev.tseki.kikidame.ui.toLatestDateText
import kotlinx.datetime.LocalDate
import kotlinx.datetime.todayIn
import kotlin.time.Clock

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
                BackgroundSyncBar(isSyncingInBackground)
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
/**
 * 左端の ★ でよく聴くを切り替える。右端は同期対象／消失の状態表示。
 *
 * M3 の `ListItem` は補足が 2 行に折り返して 3 行になると左右の要素を上に寄せる（`ListItem.kt` の `place()`、#157）。
 * 番組の行は行の高さに関わらず左右を真ん中に置く方針なので、`ListItem` は使わず [Row] で組む。
 * 左右は本文の高さが決まる前に測られるので、中身を `fillMaxHeight` で中央寄せにしても `ListItem` では真ん中に置けない。
 * 余白・最小の高さ・文字のスタイルと色は、`ListItem`（material3 1.4.0）の 1〜2 行のときの値に合わせてある。
 * 3 行になっても上下 8dp・最小 72dp のままにする（`ListItem` の 3 行の既定は上下 12dp・最小 88dp。#157 で決めた）。
 */
@Composable
private fun ProgramRow(summary: ProgramSummary, onClick: () -> Unit, onToggleStarred: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.padding(end = 16.dp)) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    IconButton(onClick = onToggleStarred) {
                        if (summary.program.starred) {
                            Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.program_list_star_remove), tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Outlined.StarOutline, contentDescription = stringResource(R.string.program_list_star_add))
                        }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(summary.program.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    summary.toSupportingText().resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.padding(start = 16.dp)) {
                when {
                    summary.program.isGone -> Icon(Icons.Filled.CloudOff, contentDescription = stringResource(R.string.common_program_gone), tint = MaterialTheme.colorScheme.error)
                    summary.program.syncEnabled -> Icon(Icons.Filled.Sync, contentDescription = stringResource(R.string.program_list_sync_enabled), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * 絞り込みのシート（#55）。上段が検索欄（#44）、下段が「すべて」＋ 手元の番組から集めた配信元（#45、番組数付き）。
 * 検索語はその場で背後の一覧に効き、キーボードの検索（決定）か配信元の選択で閉じる。配信元が 2 種類未満なら [publishers] は空で、下段を出さない。
 * 縦に並べるので配信元が増えても横にはみ出さず、半開きで下の配信元が隠れないよう最初から全開。ドラッグでは閉じない。
 * M3 の SearchBar は全画面のサジェスト領域を持つ部品なので、その場で一覧を絞る用途には使わない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    query: String,
    onQueryChange: (String) -> Unit,
    publishers: List<Publisher>,
    selected: PublisherKey?,
    onSelectPublisher: (PublisherKey?) -> Unit,
    onDismiss: () -> Unit,
) {
    // 配信元の一覧を先頭を越えて引っ張ると、余ったドラッグがシートに渡って閉じてしまう。最初から全開でドラッグで閉じる意味は薄いので
    // シートのドラッグ操作ごと無効にする（閉じるのは外側タップ・戻る・検索キー・配信元の選択）。取っ手も引けないので出さない
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        // シートの中身は別ウィンドウに描かれるので、フォーカスとキーボードの取得もこの中で行う
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        // 開いた瞬間に検索欄へフォーカスしてキーボードを出す（開く目的の大半は検索）
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        Text(stringResource(R.string.program_list_filter), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        // 文字は本文サイズ（見出しと区別する）。× は空欄に戻すだけで閉じない。キーボードの検索（決定）で閉じて、絞った一覧を見せる
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).focusRequester(focusRequester),
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = { Text(stringResource(R.string.program_list_filter_placeholder)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.program_list_clear_query))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onDismiss() }),
        )
        if (publishers.isNotEmpty()) {
            Text(stringResource(R.string.program_list_publisher), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 4.dp))
            // キーボードの上に収める（imePadding）。シートの高さいっぱいで測ると「全部収まっている」扱いになって
            // 一覧がスクロールせず、ドラッグがシートに渡って閉じてしまう
            LazyColumn(Modifier.weight(1f, fill = false).imePadding()) {
                item(key = "all") {
                    PublisherChoice(stringResource(R.string.program_list_all), publishers.sumOf { it.programCount }, selected == null) { onSelectPublisher(null); onDismiss() }
                }
                // 「すべて」「配信元なし」と配信元名が衝突しないよう接頭辞を付ける
                items(publishers, key = { it.key.name?.let { n -> "publisher:$n" } ?: "none" }) { publisher ->
                    PublisherChoice(publisher.key.label.resolve(), publisher.programCount, publisher.key == selected) { onSelectPublisher(publisher.key); onDismiss() }
                }
                // 末尾の余白は一覧の中に置く（外に置くと、配信元が多くて一覧が高さを使い切ったとき 0 になる）
                item { Spacer(Modifier.padding(bottom = 32.dp)) }
            }
        } else {
            Spacer(Modifier.padding(bottom = 32.dp))
        }
    }
}
@Composable
private fun PublisherChoice(label: String, programCount: Int, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(label) },
        trailingContent = { Text("$programCount", style = MaterialTheme.typography.bodyMedium) },
    )
}
/**
 * 効いている絞り込みを 1 つずつチップで示す（検索語は「」で囲み、配信元は配信元名）。チップのタップ（× を含む）でその絞り込みだけ解除する。
 * × だけを別の clickable にすると当たり判定が小さくなるので、チップ全体を解除にする。変えるのは TopAppBar の絞り込みから。
 */
@Composable
private fun FilterChipsRow(query: String, publisher: PublisherKey?, onClearQuery: () -> Unit, onClearPublisher: () -> Unit) {
    if (query.isEmpty() && publisher == null) return
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (query.isNotEmpty()) {
            InputChip(
                selected = true,
                onClick = onClearQuery,
                label = { Text(stringResource(R.string.program_list_query_chip, query), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.program_list_clear_query_filter)) },
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (publisher != null) {
            InputChip(
                selected = true,
                onClick = onClearPublisher,
                label = { Text(publisher.label.resolve(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.program_list_clear_publisher_filter)) },
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}
/** 絞り込みが効いていて該当が無いとき。番組自体が無いのとは別物なので、更新の案内は出さない。 */
@Composable
private fun NoMatch(query: String, publisher: PublisherKey?) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                // 「番組一覧」は配信元だけで 0 件にはならない（配信元の候補は手元の番組から集めるので）。
                // 「よく聴く」は配信元だけでも 0 件になる（その配信元によく聴く番組が無い）
                val normalized = ProgramFilter.normalize(query)
                Text(
                    when {
                        normalized.isEmpty() && publisher != null ->
                            stringResource(R.string.program_list_no_starred_in_publisher, publisher.label.resolve())
                        publisher != null -> stringResource(R.string.program_list_no_match_in_publisher, normalized, publisher.label.resolve())
                        else -> stringResource(R.string.program_list_no_match, normalized)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
/**
 * 行の補足（#41）: `配信元 · 未再生 N / 手元 L · 全 E 回 · 最新 MM-DD`。
 * 未再生 0 なら「未再生 N /」を省き、手元 = 全なら「全 E 回」を「回」に畳む（「/」が 2 つ並ばないよう「全」の前は「·」）。
 * 配信元が無い・各回が無い（最新なし）ならその部分を省き、消失なら末尾に「サーバ上で見つかりません」を足す。
 */
internal fun ProgramSummary.toSupportingText(today: LocalDate = Clock.System.todayIn(PublishedAt.ZONE)): UiText {
    val unplayed = unplayedLocalCount > 0
    val all = localEpisodeCount == episodeCount
    val count = when {
        unplayed && all -> UiText.Res(R.string.program_list_unplayed_on_device, unplayedLocalCount, localEpisodeCount)
        unplayed -> UiText.Res(R.string.program_list_unplayed_on_device_of_total, unplayedLocalCount, localEpisodeCount, episodeCount)
        all -> UiText.Res(R.string.program_list_on_device, localEpisodeCount)
        else -> UiText.Res(R.string.program_list_on_device_of_total, localEpisodeCount, episodeCount)
    }
    val latest = latestPublishedAt?.let { UiText.Res(R.string.program_list_latest, it.toLatestDateText(today)) }
    val gone = if (program.isGone) UiText.Res(R.string.common_program_gone) else null
    return UiText.Joined(listOfNotNull(program.publisherName?.let(UiText::Plain), count, latest, gone), UiText.Plain(" · "))
}
@Composable
private fun EmptyPrograms(canRefresh: Boolean) {
    // PullToRefreshBox の中身はスクロール可能である必要があるので LazyColumn で包む
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.program_list_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (canRefresh) stringResource(R.string.program_list_empty_pull) else stringResource(R.string.program_list_empty_connect),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
/** 空のタブの案内（#151。「続きから」「よく聴く」）。引っ張って更新できるよう LazyColumn で包む。 */
@Composable
internal fun PageMessage(text: String) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
    }
}
