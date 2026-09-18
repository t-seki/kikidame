package dev.tseki.jellyfinradio.ui.player
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.jellyfinradio.ui.toClockText
import kotlin.time.Duration.Companion.milliseconds
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onBack: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val played by viewModel.played.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.programName ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.setPlayed(!played) }, enabled = state.episodeId != null) {
                        if (played) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "再生済み（タップで未再生に）")
                        } else {
                            Icon(Icons.Outlined.Circle, contentDescription = "未再生（タップで再生済みに）")
                        }
                    }
                },
            )
        },
    ) { padding ->
        // 左右スワイプで秒単位のシーク（#29）。画面中央の領域から始めたドラッグだけ拾い、シークバーの上では
        // Slider が勝つ（自分でドラッグを消費する）。ドラッグ中は開始時の位置からの差分と目標位置を表示し、
        // 指を離した時点で 1 回だけシークする
        val latest by rememberUpdatedState(state)
        val density = LocalDensity.current
        var swipeStartMs by remember { mutableStateOf(0L) }
        var swipeOffsetDp by remember { mutableStateOf<Float?>(null) }
        val swipeTargetMs = swipeOffsetDp?.let { SwipeSeek.targetMs(swipeStartMs, it, state.durationMs) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { start ->
                            if (SwipeSeek.isInCenterArea(start.x, start.y, size.width.toFloat(), size.height.toFloat())) {
                                swipeStartMs = latest.positionMs
                                swipeOffsetDp = 0f
                            }
                        },
                        onDragEnd = {
                            swipeOffsetDp?.let { viewModel.seekTo(SwipeSeek.targetMs(swipeStartMs, it, latest.durationMs)) }
                            swipeOffsetDp = null
                        },
                        onDragCancel = { swipeOffsetDp = null },
                        onHorizontalDrag = { change, dragAmount ->
                            // 端から始めたドラッグ（swipeOffsetDp == null）は拾わない
                            val current = swipeOffsetDp ?: return@detectHorizontalDragGestures
                            change.consume()
                            swipeOffsetDp = current + with(density) { dragAmount.toDp().value }
                        },
                    )
                }
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(state.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            // スワイプ中だけ見せる。場所は常に確保してレイアウトが跳ねないようにする
            Text(
                text = swipeTargetMs?.let { "${SwipeSeek.deltaText(it - swipeStartMs)} → ${it.milliseconds.toClockText()}" } ?: " ",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.alpha(if (swipeTargetMs != null) 1f else 0f),
            )
            Spacer(Modifier.height(24.dp))
            SeekBar(
                positionMs = swipeTargetMs ?: state.positionMs,
                durationMs = state.durationMs,
                onSeek = viewModel::seekTo,
            )
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = viewModel::previous, enabled = state.hasPrevious) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "前の回", Modifier.size(32.dp))
                }
                IconButton(onClick = viewModel::seekBack) {
                    Icon(Icons.Default.Replay10, contentDescription = "10 秒戻る", Modifier.size(32.dp))
                }
                FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(72.dp)) {
                    if (state.isPlaying) {
                        Icon(Icons.Default.Pause, contentDescription = "一時停止", Modifier.size(40.dp))
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "再生", Modifier.size(40.dp))
                    }
                }
                IconButton(onClick = viewModel::seekForward) {
                    Icon(Icons.Default.Forward10, contentDescription = "10 秒進む", Modifier.size(32.dp))
                }
                IconButton(onClick = viewModel::next, enabled = state.hasNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "次の回", Modifier.size(32.dp))
                }
            }
        }
    }
}
/** ドラッグ中は指の位置を表示し、離した時点で 1 回だけシークする。 */
@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: positionMs.toFloat()
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = shown.coerceIn(0f, durationMs.coerceAtLeast(1).toFloat()),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
            enabled = durationMs > 0,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(shown.toLong().milliseconds.toClockText(), style = MaterialTheme.typography.labelMedium)
            Text(durationMs.milliseconds.toClockText(), style = MaterialTheme.typography.labelMedium)
        }
    }
}
