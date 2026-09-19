package dev.tseki.jellyfinradio.ui.player
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.jellyfinradio.domain.PlaybackSpeed
import dev.tseki.jellyfinradio.playback.SleepTimer
import dev.tseki.jellyfinradio.ui.toClockText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onBack: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val played by viewModel.played.collectAsStateWithLifecycle()
    val subtitle by viewModel.subtitle.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    var showSpeedSheet by remember { mutableStateOf(false) }
    val sleepTimerLabel by viewModel.sleepTimerLabel.collectAsStateWithLifecycle()
    val sleepTimerSet by viewModel.sleepTimerSet.collectAsStateWithLifecycle()
    var showSleepSheet by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.programName ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        // 左右スワイプで秒単位のシーク（#29）。画面中央の領域から始めたドラッグだけ拾い、シークバーの上では
        // Slider が勝つ（自分でドラッグを消費する）。ボタンの上から始めても拾う（タップはタッチスロップ内なので混ざらない）。
        // ドラッグ中は開始時の位置からの差分と目標位置を表示し、指を離した時点で 1 回だけシークする。
        // 尺が分かるまでは始めない（SeekBar の enabled と同じ）
        val latest by rememberUpdatedState(state)
        val density = LocalDensity.current
        var swipeStartMs by remember { mutableStateOf(0L) }
        var swipeOffsetDp by remember { mutableStateOf<Float?>(null) }
        val swipeTargetMs = swipeOffsetDp?.let { SwipeSeek.targetMs(swipeStartMs, it, state.durationMs) }
        // ドラッグ中に回が切り替わったら（自動遷移）、古い回の位置を基準にシークしないよう破棄する
        LaunchedEffect(state.episodeId) { swipeOffsetDp = null }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { start ->
                            if (latest.durationMs > 0 &&
                                SwipeSeek.isInCenterArea(start.x, start.y, size.width.toFloat(), size.height.toFloat())
                            ) {
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
                            // 中央の領域の外から始めた・尺が無い・回が切り替わった（swipeOffsetDp == null）なら拾わない
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
            // `放送局 · 出演者`（#70）。無い回や読み込み前も 1 行分の場所は確保して、回が進んだときにタイトルが跳ねないようにする
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle ?: " ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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
            // シークバーの下: 倍速（#35）・再生済み・スリープタイマー（#36）を中央 3 列に（#57）。
            // 片手で使うので、親指が届かない TopAppBar ではなく主操作のすぐ上にまとめる。
            // アイコンの contentDescription はラベルが名前にならないもの（倍速の「1×」、スリープの残り時間）にだけ文脈を足す
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                SubAction(Icons.Filled.Speed, contentDescription = "倍速", label = PlaybackSpeed.label(speed)) { showSpeedSheet = true }
                if (played) {
                    SubAction(Icons.Default.CheckCircle, contentDescription = null, label = "再生済み", enabled = state.episodeId != null) { viewModel.setPlayed(false) }
                } else {
                    SubAction(Icons.Outlined.Circle, contentDescription = null, label = "未再生", enabled = state.episodeId != null) { viewModel.setPlayed(true) }
                }
                if (sleepTimerSet) {
                    SubAction(Icons.Filled.Bedtime, contentDescription = "スリープタイマー", label = sleepTimerLabel ?: "スリープ") { showSleepSheet = true }
                } else {
                    SubAction(Icons.Outlined.Bedtime, contentDescription = null, label = "スリープ") { showSleepSheet = true }
                }
            }
            Spacer(Modifier.height(8.dp))
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
    if (showSpeedSheet) {
        SpeedSheet(current = speed, onSelect = viewModel::setSpeed, onDismiss = { showSpeedSheet = false })
    }
    if (showSleepSheet) {
        SleepTimerSheet(
            isSet = sleepTimerSet,
            onSelectAfter = viewModel::setSleepTimer,
            onSelectEndOfEpisode = viewModel::setSleepTimerToEndOfEpisode,
            onDismiss = { showSleepSheet = false },
        )
    }
}
/** シークバー下の 3 列の 1 つ。アイコン＋短いラベル。左右のパディングを詰めて、狭い画面（360dp）でも `1.75×` `再生済み` `1:00:00` が並ぶ。 */
@Composable
private fun SubAction(icon: ImageVector, contentDescription: String?, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
        Icon(icon, contentDescription = contentDescription, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
/** 倍速を選ぶシート。保存と反映は ViewModel → 設定 → サービスの経路で、ここは選択肢を見せるだけ。狭い画面では折り返す。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SpeedSheet(current: Float, onSelect: (Float) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("倍速", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in PlaybackSpeed.CHOICES) {
                FilterChip(
                    selected = choice == current,
                    onClick = { onSelect(choice); onDismiss() },
                    label = { Text(PlaybackSpeed.label(choice)) },
                )
            }
        }
        Spacer(Modifier.padding(bottom = 32.dp))
    }
}

/** スリープタイマーを選ぶシート。時間・この回の終わりまで・解除（設定中のみ）。実行はサービス側。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerSheet(
    isSet: Boolean,
    onSelectAfter: (Duration?) -> Unit,
    onSelectEndOfEpisode: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("スリープタイマー", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Text(
            "時間が来るか今の回が終わったら一時停止します。一時停止している間は時間が進みません",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        FlowRow(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in SleepTimer.CHOICES) {
                FilterChip(selected = false, onClick = { onSelectAfter(choice); onDismiss() }, label = { Text("${choice.inWholeMinutes} 分") })
            }
            FilterChip(selected = false, onClick = { onSelectEndOfEpisode(); onDismiss() }, label = { Text("この回の終わりまで") })
            if (isSet) {
                FilterChip(selected = false, onClick = { onSelectAfter(null); onDismiss() }, label = { Text("解除") })
            }
        }
        Spacer(Modifier.padding(bottom = 32.dp))
    }
}

/** ドラッグ中は指の位置を表示し、離した時点で 1 回だけシークする。時間は titleMedium（#75。labelMedium は小さくて読みにくかった。大きな時間表示は見送り）。 */
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
            Text(shown.toLong().milliseconds.toClockText(), style = MaterialTheme.typography.titleMedium)
            Text(durationMs.milliseconds.toClockText(), style = MaterialTheme.typography.titleMedium)
        }
    }
}
