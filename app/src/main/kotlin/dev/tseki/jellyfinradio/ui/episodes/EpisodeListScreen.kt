package dev.tseki.jellyfinradio.ui.episodes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.jellyfinradio.domain.EpisodeId
import dev.tseki.jellyfinradio.domain.EpisodeWithState
import dev.tseki.jellyfinradio.ui.toAiredDateText
import dev.tseki.jellyfinradio.ui.toClockText
import kotlin.time.Duration
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeListScreen(
    onEpisodeClick: (EpisodeId) -> Unit,
    onBack: () -> Unit,
    viewModel: EpisodeListViewModel = hiltViewModel(),
) {
    val program by viewModel.program.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(program?.name ?: "")
                        program?.stationName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            items(episodes.orEmpty(), key = { it.episode.id.value }) { item ->
                EpisodeRow(
                    item = item,
                    onClick = { onEpisodeClick(item.episode.id) },
                    onTogglePlayed = { viewModel.setPlayed(item.episode.id, item.playback?.played != true) },
                )
                HorizontalDivider()
            }
        }
    }
}
@Composable
private fun EpisodeRow(item: EpisodeWithState, onClick: () -> Unit, onTogglePlayed: () -> Unit) {
    val played = item.playback?.played == true
    val position = item.playback?.position ?: Duration.ZERO
    val runtime = item.episode.runtime
    ListItem(
        modifier = Modifier.clickable(enabled = item.isPlayable, onClick = onClick),
        headlineContent = { Text(item.episode.title) },
        supportingContent = {
            Column {
                Text("${item.episode.airedAt.toAiredDateText()} · ${runtime.toClockText()}")
                if (!played && position > Duration.ZERO && runtime > Duration.ZERO) {
                    LinearProgressIndicator(
                        progress = { (position / runtime).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onTogglePlayed) {
                if (played) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "再生済み（タップで未再生に）")
                } else {
                    Icon(Icons.Outlined.Circle, contentDescription = "未再生（タップで再生済みに）")
                }
            }
        },
    )
}
