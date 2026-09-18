package dev.tseki.jellyfinradio.ui.programs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Settings
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
import dev.tseki.jellyfinradio.domain.ProgramId
import dev.tseki.jellyfinradio.domain.ProgramSummary
import dev.tseki.jellyfinradio.ui.toAiredDateText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramListScreen(
    onProgramClick: (ProgramId) -> Unit,
    onSettingsClick: () -> Unit,
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
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.program.id.value }) { summary ->
                        ProgramRow(summary, onClick = { onProgramClick(summary.program.id) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgramRow(summary: ProgramSummary, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(summary.program.name) },
        supportingContent = {
            val station = summary.program.stationName
            val count = if (summary.localEpisodeCount == summary.episodeCount) {
                "${summary.episodeCount} 回"
            } else {
                "手元 ${summary.localEpisodeCount} / 全 ${summary.episodeCount} 回"
            }
            val latest = summary.latestAiredAt?.let { "最新 ${it.toAiredDateText()}" }
            Text(listOfNotNull(station, count, latest).joinToString(" · "))
        },
        trailingContent = {
            if (summary.program.syncEnabled) {
                Icon(Icons.Filled.Sync, contentDescription = "同期対象", tint = MaterialTheme.colorScheme.primary)
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
