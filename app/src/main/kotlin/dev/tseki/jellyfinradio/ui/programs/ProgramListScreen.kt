package dev.tseki.jellyfinradio.ui.programs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.ProgramId
import dev.tseki.jellyfinradio.domain.ProgramSummary
import dev.tseki.jellyfinradio.ui.player.MiniPlayer
import dev.tseki.jellyfinradio.ui.programs.ProgramFilter.Station
import dev.tseki.jellyfinradio.ui.programs.ProgramFilter.StationKey
import dev.tseki.jellyfinradio.ui.toAiredDateText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramListScreen(
    onProgramClick: (ProgramId) -> Unit,
    onSettingsClick: () -> Unit,
    onNowPlayingClick: (EpisodeId) -> Unit,
    viewModel: ProgramListViewModel = hiltViewModel(),
) {
    val filtered by viewModel.filtered.collectAsStateWithLifecycle()
    val canRefresh by viewModel.canRefresh.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showStationSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    if (showStationSheet) {
        StationSheet(
            stations = filtered?.stations.orEmpty(),
            selected = filtered?.station,
            onSelect = viewModel::selectStation,
            onDismiss = { showStationSheet = false },
        )
    }
    // 検索欄が開いているときの戻るボタンは検索を閉じるだけ（番組一覧は根なので、そのままだとアプリを抜ける）
    BackHandler(enabled = isSearching, onBack = viewModel::stopSearch)
    Scaffold(
        topBar = {
            // タイトルは検索中も残し、入力欄はその下に一段出す（#44）。虫眼鏡は検索中は × になる
            Column {
                TopAppBar(
                    title = { Text("番組") },
                    actions = {
                        // 放送局で絞る（#45）。局が 2 種類未満なら絞る意味が無いので出さない。選択中は点を付ける
                        val stations = filtered?.stations.orEmpty()
                        if (stations.size >= 2) {
                            IconButton(onClick = { showStationSheet = true }) {
                                BadgedBox(badge = { if (filtered?.station != null) Badge() }) {
                                    Icon(Icons.Default.FilterList, contentDescription = "放送局で絞る")
                                }
                            }
                        }
                        when {
                            isSearching -> IconButton(onClick = viewModel::stopSearch) {
                                Icon(Icons.Default.Close, contentDescription = "検索を閉じる")
                            }
                            // 絞る対象が無いときは虫眼鏡を出さない
                            filtered?.stations?.isNotEmpty() == true -> IconButton(onClick = viewModel::startSearch) {
                                Icon(Icons.Default.Search, contentDescription = "検索")
                            }
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = "設定")
                        }
                    },
                )
                if (isSearching) SearchField(query, onQueryChange = viewModel::setQuery)
                filtered?.station?.let { station ->
                    SelectedStationRow(station, onClick = { showStationSheet = true }, onClear = viewModel::clearStation)
                }
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
            val state = filtered
            val list = state?.programs
            when {
                state == null || list == null -> Box(Modifier.fillMaxSize())
                // 番組自体が無い（局も集まらない）なら、検索中でも「一致なし」ではなく空の案内
                state.stations.isEmpty() -> EmptyPrograms(canRefresh)
                list.isEmpty() -> NoMatch(query, state.station)
                else -> {
                    val listState = rememberLazyListState()
                    // 検索語か局が変わるたびに先頭へ（絞った結果は先頭から見たい。解除したときも先頭に戻す）。
                    // 末尾の空白など絞り込みに効かない変化では動かさず、初回も動かさない（番組から戻ったときに復元された位置を潰さない）
                    val filterKey = ProgramFilter.normalize(query) to state.station
                    var seenKey by remember { mutableStateOf(filterKey) }
                    LaunchedEffect(filterKey) {
                        if (filterKey != seenKey) {
                            seenKey = filterKey
                            listState.scrollToItem(0)
                        }
                    }
                    if (state.isFiltering) {
                        // 絞り込み中は該当が少ないので節に分けずフラットに出す（#45 の局チップも同じ規則）
                        LazyColumn(Modifier.fillMaxSize(), state = listState) {
                            programItems(list, onProgramClick, viewModel::setStarred)
                        }
                    } else {
                        // よく聴く番組は上の節にまとめる（重複させない）。無ければ節ごと出さない。節内は従来どおり最新の放送日順
                        val (starred, others) = list.partition { it.program.starred }
                        // 最初の ★ で見出しが先頭行の上に挿入されると、キー基準のスクロール位置維持で見出しが画面外に出る。
                        // 先頭付近にいるときだけ先頭に戻す（下の方を見ているときは動かさない）
                        LaunchedEffect(starred.isNotEmpty()) {
                            if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
                        }
                        LazyColumn(Modifier.fillMaxSize(), state = listState) {
                            if (starred.isNotEmpty()) {
                                item(key = "header-starred") { SectionHeader("よく聴く") }
                                programItems(starred, onProgramClick, viewModel::setStarred)
                                // 全部がよく聴くなら「その他」の見出しも出さない
                                if (others.isNotEmpty()) item(key = "header-others") { SectionHeader("その他") }
                            }
                            programItems(others, onProgramClick, viewModel::setStarred)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 検索の入力欄（#44）。TopAppBar の下に一段置き、文字は本文サイズ（タイトルと区別する）。× は空欄に戻すだけで閉じない（閉じるのは TopAppBar の ×）。
 * M3 の SearchBar は全画面のサジェスト領域を持つ部品なので、その場で一覧を絞る用途には使わない。
 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // 開いた瞬間にフォーカスとキーボードを出す
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        textStyle = MaterialTheme.typography.bodyLarge,
        placeholder = { Text("番組名・放送局名") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "検索語を消す")
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
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
@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
    )
}
/** 左端の ★ でよく聴くを切り替える。右端は同期対象／消失の状態表示。 */
@Composable
private fun ProgramRow(summary: ProgramSummary, onClick: () -> Unit, onToggleStarred: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            IconButton(onClick = onToggleStarred) {
                if (summary.program.starred) {
                    Icon(Icons.Filled.Star, contentDescription = "よく聴く（タップで外す）", tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Outlined.StarOutline, contentDescription = "よく聴くに入れる")
                }
            }
        },
        headlineContent = { Text(summary.program.name) },
        supportingContent = {
            val station = summary.program.stationName
            val count = if (summary.localEpisodeCount == summary.episodeCount) {
                "${summary.episodeCount} 回"
            } else {
                "手元 ${summary.localEpisodeCount} / 全 ${summary.episodeCount} 回"
            }
            val latest = summary.latestAiredAt?.let { "最新 ${it.toAiredDateText()}" }
            val gone = if (summary.program.isGone) "サーバ上で見つかりません" else null
            Text(listOfNotNull(station, count, latest, gone).joinToString(" · "))
        },
        trailingContent = {
            when {
                summary.program.isGone -> Icon(Icons.Filled.CloudOff, contentDescription = "サーバ上で見つかりません", tint = MaterialTheme.colorScheme.error)
                summary.program.syncEnabled -> Icon(Icons.Filled.Sync, contentDescription = "同期対象", tint = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

/**
 * 放送局を選ぶシート（#45）。「すべて」＋ 手元の番組から集めた局を番組数付きで縦に並べる。
 * 局が増えても横にはみ出さず全局が一目で分かる。選んだら閉じる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationSheet(
    stations: List<Station>,
    selected: StationKey?,
    onSelect: (StationKey?) -> Unit,
    onDismiss: () -> Unit,
) {
    // 半開きだと下の局が隠れて「全局が一目」にならないので最初から全開
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("放送局", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn {
            item(key = "all") {
                StationChoice("すべて", stations.sumOf { it.programCount }, selected == null) { onSelect(null); onDismiss() }
            }
            // 「すべて」「局なし」と局名が衝突しないよう接頭辞を付ける
            items(stations, key = { it.key.name?.let { n -> "station:$n" } ?: "none" }) { station ->
                StationChoice(station.key.label, station.programCount, station.key == selected) { onSelect(station.key); onDismiss() }
            }
            item { Spacer(Modifier.padding(bottom = 32.dp)) }
        }
    }
}
@Composable
private fun StationChoice(label: String, programCount: Int, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(label) },
        trailingContent = { Text("$programCount", style = MaterialTheme.typography.bodyMedium) },
    )
}
/** 選択中の局を 1 チップで示す。タップでシートを開き直し、× で「すべて」に戻す。未選択なら何も出さない。 */
@Composable
private fun SelectedStationRow(station: StationKey, onClick: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        InputChip(
            selected = true,
            onClick = onClick,
            label = { Text(station.label) },
            trailingIcon = {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "局の絞り込みを解除",
                    modifier = Modifier.clickable(onClick = onClear),
                )
            },
        )
    }
}
/** 絞り込みが効いていて該当が無いとき。番組自体が無いのとは別物なので、更新の案内は出さない。 */
@Composable
private fun NoMatch(query: String, station: StationKey?) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                // 局だけで 0 件にはならない（チップは手元の番組から集めるので）。検索語は必ずある
                val prefix = station?.let { "${it.label}に " } ?: ""
                Text(
                    "$prefix\"${ProgramFilter.normalize(query)}\" に一致する番組がありません",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
@Composable
private fun EmptyPrograms(canRefresh: Boolean) {
    // PullToRefreshBox の中身はスクロール可能である必要があるので LazyColumn で包む
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("番組がありません", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (canRefresh) "引っ張って更新するとサーバから取得します" else "設定からサーバに接続してください",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
