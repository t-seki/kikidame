package dev.tseki.jellyfinradio.ui.programs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
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
import dev.tseki.jellyfinradio.BuildConfig
import dev.tseki.jellyfinradio.domain.ProgramId
import dev.tseki.jellyfinradio.domain.ProgramSummary
import dev.tseki.jellyfinradio.ui.toAiredDateText
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramListScreen(
    onProgramClick: (ProgramId) -> Unit,
    viewModel: ProgramListViewModel = hiltViewModel(),
) {
    val programs by viewModel.programs.collectAsStateWithLifecycle()
    val seedMessage by viewModel.seedMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(seedMessage) {
        seedMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeSeedMessage()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("番組") },
                actions = {
                    if (BuildConfig.DEBUG) {
                        IconButton(onClick = viewModel::runSeed) {
                            Icon(Icons.Default.CloudDownload, contentDescription = "シード")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val list = programs
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyPrograms(viewModel.seedRoot, Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding)) {
                items(list, key = { it.program.id.value }) { summary ->
                    ProgramRow(summary, onClick = { onProgramClick(summary.program.id) })
                    HorizontalDivider()
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
            val latest = summary.latestAiredAt?.let { "最新 ${it.toAiredDateText()}" }
            Text(listOfNotNull(station, "${summary.episodeCount} 回", latest).joinToString(" · "))
        },
    )
}
@Composable
private fun EmptyPrograms(seedRoot: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("番組がありません", style = MaterialTheme.typography.titleMedium)
            if (BuildConfig.DEBUG && seedRoot != null) {
                Text(
                    "$seedRoot/<放送局>/<番組>/ に音声ファイルを adb push して、右上のシードを押してください",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
