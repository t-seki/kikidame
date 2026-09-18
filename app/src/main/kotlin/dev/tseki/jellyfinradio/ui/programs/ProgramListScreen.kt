package dev.tseki.jellyfinradio.ui.programs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.ProgramId
import dev.tseki.jellyfinradio.domain.ProgramSummary
import dev.tseki.jellyfinradio.ui.player.MiniPlayer
import dev.tseki.jellyfinradio.ui.toAiredDateText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramListScreen(
    onProgramClick: (ProgramId) -> Unit,
    onSettingsClick: () -> Unit,
    onNowPlayingClick: (EpisodeId) -> Unit,
    viewModel: ProgramListViewModel = hiltViewModel(),
) {
    val programs by viewModel.programs.collectAsStateWithLifecycle()
    val canRefresh by viewModel.canRefresh.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("番組") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = { MiniPlayer(onClick = onNowPlayingClick) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { if (canRefresh) viewModel.refresh() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val list = programs
            when {
                list == null -> Box(Modifier.fillMaxSize())
                list.isEmpty() -> EmptyPrograms(canRefresh)
                else -> {
                    // よく聴く番組は上の節にまとめる（重複させない）。無ければ節ごと出さない。節内は従来どおり最新の放送日順
                    val (starred, others) = list.partition { it.program.starred }
                    val listState = rememberLazyListState()
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
