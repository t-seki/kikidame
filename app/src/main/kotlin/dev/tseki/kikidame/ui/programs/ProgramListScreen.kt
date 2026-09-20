package dev.tseki.kikidame.ui.programs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ProgramSummary
import dev.tseki.kikidame.ui.SectionTitle
import dev.tseki.kikidame.ui.player.MiniPlayer
import dev.tseki.kikidame.ui.programs.ProgramFilter.Publisher
import dev.tseki.kikidame.ui.programs.ProgramFilter.PublisherKey
import dev.tseki.kikidame.domain.PublishedAt
import dev.tseki.kikidame.ui.toLatestDateText
import kotlinx.datetime.LocalDate
import kotlinx.datetime.todayIn
import kotlin.time.Clock

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
    val snackbarHostState = remember { SnackbarHostState() }
    // 他のシートと同じく remember（プロセス死で開き直さない。絞り込みの状態も復元しないので）
    var showFilterSheet by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    // 絞り込みの入口（#55）は番組が 1 つでもあれば出す。配信元の段は 2 種類以上のときだけ（1 種類なら絞る意味が無い）
    val publishers = filtered?.publishers.orEmpty()
    if (showFilterSheet && publishers.isNotEmpty()) {
        FilterSheet(
            query = query,
            onQueryChange = viewModel::setQuery,
            publishers = publishers.takeIf { it.size >= 2 }.orEmpty(),
            selected = filtered?.publisher,
            onSelectPublisher = viewModel::selectPublisher,
            onDismiss = { showFilterSheet = false },
        )
    }
    // 絞り込みが効いている間の戻るボタンは絞り込みを解除するだけ（番組一覧は根なので、そのままだとアプリを抜ける）。
    // シートが開いている間はシート自身が戻るで閉じるので、こちらは効かせない
    BackHandler(enabled = filtered?.isFiltering == true && !showFilterSheet, onBack = viewModel::clearFilters)
    Scaffold(
        topBar = {
            // 検索（#44）と配信元（#45）の入口を 1 つの「絞り込み」に統合（#55）。効いている間は点を付け、
            // 何で絞っているかは TopAppBar 下のチップで示す
            Column {
                TopAppBar(
                    title = { Text("番組") },
                    actions = {
                        if (publishers.isNotEmpty()) {
                            IconButton(onClick = { showFilterSheet = true }) {
                                BadgedBox(badge = { if (filtered?.isFiltering == true) Badge() }) {
                                    Icon(Icons.Default.Tune, contentDescription = "絞り込み")
                                }
                            }
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = "設定")
                        }
                    },
                )
                FilterChipsRow(
                    query = ProgramFilter.normalize(query),
                    publisher = filtered?.publisher,
                    onClearQuery = { viewModel.setQuery("") },
                    onClearPublisher = { viewModel.selectPublisher(null) },
                )
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
                // 番組自体が無い（配信元も集まらない）なら、検索中でも「一致なし」ではなく空の案内
                state.publishers.isEmpty() -> EmptyPrograms(canRefresh)
                list.isEmpty() -> NoMatch(query, state.publisher)
                else -> {
                    val listState = rememberLazyListState()
                    // 検索語か配信元が変わるたびに先頭へ（絞った結果は先頭から見たい。解除したときも先頭に戻す）。
                    // 末尾の空白など絞り込みに効かない変化では動かさず、初回も動かさない（番組から戻ったときに復元された位置を潰さない）
                    val filterKey = ProgramFilter.normalize(query) to state.publisher
                    var seenKey by remember { mutableStateOf(filterKey) }
                    LaunchedEffect(filterKey) {
                        if (filterKey != seenKey) {
                            seenKey = filterKey
                            listState.scrollToItem(0)
                        }
                    }
                    if (state.isFiltering) {
                        // 何らかの絞り込みが効いていれば節に分けずフラットに出す（#44・#45 共通の規則。検索は該当が少なく節が邪魔、配信元はそれに揃えた）
                        LazyColumn(Modifier.fillMaxSize(), state = listState) {
                            programItems(list, onProgramClick, viewModel::setStarred)
                        }
                    } else {
                        // よく聴く番組は上の節にまとめる（重複させない）。無ければ節ごと出さない。節内は従来どおり最新の公開日順
                        val (starred, others) = list.partition { it.program.starred }
                        // 最初の ★ で見出しが先頭行の上に挿入されると、キー基準のスクロール位置維持で見出しが画面外に出る。
                        // 先頭付近にいるときだけ先頭に戻す（下の方を見ているときは動かさない）
                        LaunchedEffect(starred.isNotEmpty()) {
                            if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
                        }
                        LazyColumn(Modifier.fillMaxSize(), state = listState) {
                            if (starred.isNotEmpty()) {
                                item(key = "header-starred") { SectionTitle("よく聴く") }
                                programItems(starred, onProgramClick, viewModel::setStarred)
                                // 全部がよく聴くなら「その他」の見出しも出さない
                                if (others.isNotEmpty()) item(key = "header-others") { SectionTitle("その他") }
                            }
                            programItems(others, onProgramClick, viewModel::setStarred)
                        }
                    }
                }
            }
        }
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
        supportingContent = { Text(summary.toSupportingText()) },
        trailingContent = {
            when {
                summary.program.isGone -> Icon(Icons.Filled.CloudOff, contentDescription = "サーバ上で見つかりません", tint = MaterialTheme.colorScheme.error)
                summary.program.syncEnabled -> Icon(Icons.Filled.Sync, contentDescription = "同期対象", tint = MaterialTheme.colorScheme.primary)
            }
        },
    )
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
        Text("絞り込み", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        // 文字は本文サイズ（見出しと区別する）。× は空欄に戻すだけで閉じない。キーボードの検索（決定）で閉じて、絞った一覧を見せる
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).focusRequester(focusRequester),
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = { Text("番組名・配信元名") },
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
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onDismiss() }),
        )
        if (publishers.isNotEmpty()) {
            Text("配信元", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 4.dp))
            // キーボードの上に収める（imePadding）。シートの高さいっぱいで測ると「全部収まっている」扱いになって
            // 一覧がスクロールせず、ドラッグがシートに渡って閉じてしまう
            LazyColumn(Modifier.weight(1f, fill = false).imePadding()) {
                item(key = "all") {
                    PublisherChoice("すべて", publishers.sumOf { it.programCount }, selected == null) { onSelectPublisher(null); onDismiss() }
                }
                // 「すべて」「配信元なし」と配信元名が衝突しないよう接頭辞を付ける
                items(publishers, key = { it.key.name?.let { n -> "publisher:$n" } ?: "none" }) { publisher ->
                    PublisherChoice(publisher.key.label, publisher.programCount, publisher.key == selected) { onSelectPublisher(publisher.key); onDismiss() }
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
                label = { Text("「$query」", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "検索の絞り込みを解除") },
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (publisher != null) {
            InputChip(
                selected = true,
                onClick = onClearPublisher,
                label = { Text(publisher.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "配信元の絞り込みを解除") },
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
                // 配信元だけで 0 件にはならない（配信元の候補は手元の番組から集めるので）。検索語は必ずある
                val prefix = publisher?.let { "${it.label}に " } ?: ""
                Text(
                    "$prefix「${ProgramFilter.normalize(query)}」に一致する番組がありません",
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
internal fun ProgramSummary.toSupportingText(today: LocalDate = Clock.System.todayIn(PublishedAt.ZONE)): String {
    val unplayed = if (unplayedLocalCount > 0) "未再生 $unplayedLocalCount / " else ""
    val count = if (localEpisodeCount == episodeCount) {
        "${unplayed}手元 $localEpisodeCount 回"
    } else {
        "${unplayed}手元 $localEpisodeCount · 全 $episodeCount 回"
    }
    val latest = latestPublishedAt?.let { "最新 ${it.toLatestDateText(today)}" }
    val gone = if (program.isGone) "サーバ上で見つかりません" else null
    return listOfNotNull(program.publisherName, count, latest, gone).joinToString(" · ")
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
