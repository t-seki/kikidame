package dev.tseki.kikidame.ui.player
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.PlaybackSpeed
import dev.tseki.kikidame.domain.ProgramImage
import dev.tseki.kikidame.ui.EpisodeImageView
import dev.tseki.kikidame.ui.ProgramImagePreview
import dev.tseki.kikidame.ui.ProgramImageView
import dev.tseki.kikidame.ui.previewProgramImage
import dev.tseki.kikidame.playback.SleepTimer
import dev.tseki.kikidame.text.UiText
import dev.tseki.kikidame.ui.resolve
import dev.tseki.kikidame.ui.theme.KikidameTheme
import dev.tseki.kikidame.ui.toClockText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
@Composable
fun PlayerScreen(onBack: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val played by viewModel.played.collectAsStateWithLifecycle()
    val subtitle by viewModel.subtitle.collectAsStateWithLifecycle()
    val programImage by viewModel.programImage.collectAsStateWithLifecycle()
    val episodeImage by viewModel.episodeImage.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val sleepTimerLabel by viewModel.sleepTimerLabel.collectAsStateWithLifecycle()
    PlayerContent(
        state = state,
        played = played,
        subtitle = subtitle,
        programImage = programImage,
        episodeImage = episodeImage,
        speed = speed,
        sleepTimerLabel = sleepTimerLabel,
        onBack = onBack,
        onSeek = viewModel::seekTo,
        onSetPlayed = viewModel::setPlayed,
        onPrevious = viewModel::previous,
        onSeekBack = viewModel::seekBack,
        onTogglePlayPause = viewModel::togglePlayPause,
        onSeekForward = viewModel::seekForward,
        onNext = viewModel::next,
        onSetSpeed = viewModel::setSpeed,
        onSetSleepTimer = viewModel::setSleepTimer,
        onSetSleepTimerToEndOfEpisode = viewModel::setSleepTimerToEndOfEpisode,
    )
}

/**
 * 再生画面の中身。ViewModel を受け取らず、値とラムダだけで描く（プレビューから呼べるように）。
 * [swipeSeek] はふつう既定値のまま（[state] の位置・尺と [onSeek] で動く）。プレビューではスワイプ中の状態を渡す。
 * [episodeImage]（回の画像。サービスが載せたバイト列、#283）があればそれを、無ければ [programImage] を、タイトルの上の正方形の枠に、切らずに全体を出す（#264、#301。`ContentScale.Fit`。横長・縦長の画像は余りに何も置かない）。幅の 6 割を上限に、縦に収まらなければボタン類を押し出さない大きさまで縮める。
 * 無ければ何も置かない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerContent(
    state: PlayerUiState,
    played: Boolean,
    subtitle: UiText?,
    programImage: ProgramImage?,
    episodeImage: ByteArray? = null,
    speed: Float,
    sleepTimerLabel: UiText?,
    onBack: () -> Unit,
    onSeek: (Long) -> Unit,
    onSetPlayed: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onSeekBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onNext: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetSleepTimer: (Duration?) -> Unit,
    onSetSleepTimerToEndOfEpisode: () -> Unit,
    swipeSeek: SwipeSeekState = rememberSwipeSeekState(state.episodeId, state.positionMs, state.durationMs, onSeek),
) {
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showSleepSheet by remember { mutableStateOf(false) }
    var showChapterSheet by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.programName ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        // 左右スワイプで秒単位のシーク（#29）。拾う条件とジェスチャは SwipeSeekState.kt。
        // ドラッグ中は開始時の位置からの差分と目標位置を表示し、指を離した時点で 1 回だけシークする
        val swipeTargetMs = swipeSeek.targetMs
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .swipeToSeek(swipeSeek)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 番組の画像（#264）。スワイプのシークを拾う範囲（この Column）に含める。weight(fill = false) で、ほかの要素を置いた残りの高さを上限にする
            if (episodeImage != null || programImage != null) {
                Box(Modifier.weight(1f, fill = false).fillMaxWidth(0.6f), contentAlignment = Alignment.Center) {
                    if (episodeImage != null) EpisodeImageView(episodeImage, Modifier.aspectRatio(1f), ContentScale.Fit)
                    else if (programImage != null) ProgramImageView(programImage, Modifier.aspectRatio(1f), ContentScale.Fit)
                }
                Spacer(Modifier.height(16.dp))
            }
            Text(state.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            // `配信元 · 出演者`（#70）。無い回や読み込み前も 1 行分の場所は確保して、回が進んだときにタイトルが跳ねないようにする
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle?.resolve() ?: " ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it.resolve(), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            // スワイプ中は差分と目標位置（primary）、普段は今のチャプターの題（#348。控えめな色で 1 行。1 行に収まらないときだけ `basicMarquee` で横に流す（回数は限らず、各回の間に 2.5 秒止まる）。スワイプ中は流さない）。
            // チャプターの無い回・最初のチャプターの前は空白。場所は常に確保してレイアウトが跳ねないようにする
            val chapterTitle = currentChapter(state.chapters, state.positionMs)?.title
            Text(
                text = swipeTargetMs?.let { "${SwipeSeek.deltaText(it - swipeSeek.startMs).resolve()} → ${it.milliseconds.toClockText()}" }
                    ?: chapterTitle?.takeIf { it.isNotEmpty() }
                    ?: " ",
                style = MaterialTheme.typography.titleMedium,
                color = if (swipeTargetMs != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .alpha(if (swipeTargetMs != null || !chapterTitle.isNullOrEmpty()) 1f else 0f)
                    .then(if (swipeTargetMs == null) Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 2_500) else Modifier),
            )
            Spacer(Modifier.height(24.dp))
            SeekBar(
                positionMs = swipeTargetMs ?: state.positionMs,
                durationMs = state.durationMs,
                onSeek = onSeek,
            )
            // シークバーの下: 倍速（#35）・再生済み・スリープタイマー（#36）を中央 3 列に（#57）。チャプターのある回だけ 4 列目にチャプター（#348）。
            // 片手で使うので、親指が届かない TopAppBar ではなく主操作のすぐ上にまとめる。
            // アイコンの contentDescription はラベルが名前にならないもの（倍速の「1×」、スリープの残り時間）にだけ文脈を足す
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                SubAction(Icons.Filled.Speed, contentDescription = stringResource(R.string.player_speed), label = PlaybackSpeed.label(speed)) { showSpeedSheet = true }
                if (played) {
                    SubAction(Icons.Default.CheckCircle, contentDescription = null, label = stringResource(R.string.common_played), enabled = state.episodeId != null) { onSetPlayed(false) }
                } else {
                    SubAction(Icons.Outlined.Circle, contentDescription = null, label = stringResource(R.string.common_unplayed), enabled = state.episodeId != null) { onSetPlayed(true) }
                }
                val sleepLabel = sleepTimerLabel
                if (sleepLabel != null) {
                    SubAction(Icons.Filled.Bedtime, contentDescription = stringResource(R.string.player_sleep_timer), label = sleepLabel.resolve()) { showSleepSheet = true }
                } else {
                    SubAction(Icons.Outlined.Bedtime, contentDescription = null, label = stringResource(R.string.player_sleep)) { showSleepSheet = true }
                }
                if (state.chapters.isNotEmpty()) {
                    SubAction(Icons.AutoMirrored.Filled.List, contentDescription = null, label = stringResource(R.string.player_chapters)) { showChapterSheet = true }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onPrevious, enabled = state.hasPrevious) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.player_previous), Modifier.size(32.dp))
                }
                IconButton(onClick = onSeekBack) {
                    Icon(Icons.Default.Replay10, contentDescription = stringResource(R.string.player_seek_back), Modifier.size(32.dp))
                }
                FilledIconButton(onClick = onTogglePlayPause, modifier = Modifier.size(72.dp)) {
                    if (state.isPlaying) {
                        Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.common_pause), Modifier.size(40.dp))
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.common_play), Modifier.size(40.dp))
                    }
                }
                IconButton(onClick = onSeekForward) {
                    Icon(Icons.Default.Forward10, contentDescription = stringResource(R.string.player_seek_forward), Modifier.size(32.dp))
                }
                IconButton(onClick = onNext, enabled = state.hasNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.player_next), Modifier.size(32.dp))
                }
            }
        }
    }
    if (showSpeedSheet) {
        SpeedSheet(current = speed, onSelect = onSetSpeed, onDismiss = { showSpeedSheet = false })
    }
    if (showChapterSheet) {
        ChapterSheet(
            chapters = state.chapters,
            positionMs = state.positionMs,
            onSelect = onSeek,
            onDismiss = { showChapterSheet = false },
        )
    }
    if (showSleepSheet) {
        SleepTimerSheet(
            isSet = sleepTimerLabel != null,
            onSelectAfter = onSetSleepTimer,
            onSelectEndOfEpisode = onSetSleepTimerToEndOfEpisode,
            onDismiss = { showSleepSheet = false },
        )
    }
}
/** シークバー下の 3〜4 列の 1 つ。アイコン＋短いラベル。左右のパディングを詰めて、3 列のときは、狭い画面（360dp）でも `1.75×` `再生済み` `1:00:00` が並ぶことを確かめた。4 列（チャプターのある回）は 360dp でも英語でも収まるか未確認（#348 の実機チェックリスト）。 */
@Composable
private fun SubAction(icon: ImageVector, contentDescription: String?, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
        Icon(icon, contentDescription = contentDescription, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
/**
 * チャプターの一覧のシート（#348）。各行は開始の時刻と題。今のチャプターは面の色を変えて再生中の印を付け、開いたときにその行までスクロールする。
 * 行を選ぶとその開始へシークして閉じる（再生中か一時停止中かは変えない）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(chapters: List<EpisodeChapter>, positionMs: Long, onSelect: (Long) -> Unit, onDismiss: () -> Unit) {
    val current = currentChapter(chapters, positionMs)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = chapters.indexOf(current).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(stringResource(R.string.player_chapters), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
            items(chapters) { chapter ->
                val isCurrent = chapter == current
                Surface(
                    onClick = { onSelect(chapter.startMs); onDismiss() },
                    color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(chapter.startMs.milliseconds.toClockText(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(chapter.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (isCurrent) Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** 倍速を選ぶシート。保存と反映は ViewModel → 設定 → サービスの経路で、ここは選択肢を見せるだけ。狭い画面では折り返す。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SpeedSheet(current: Float, onSelect: (Float) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(stringResource(R.string.player_speed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(stringResource(R.string.player_sleep_timer), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Text(
            stringResource(R.string.player_sleep_description),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        FlowRow(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in SleepTimer.CHOICES) {
                FilterChip(selected = false, onClick = { onSelectAfter(choice); onDismiss() }, label = { Text(pluralStringResource(R.plurals.player_sleep_minutes, choice.inWholeMinutes.toInt(), choice.inWholeMinutes.toInt())) })
            }
            FilterChip(selected = false, onClick = { onSelectEndOfEpisode(); onDismiss() }, label = { Text(stringResource(R.string.player_sleep_until_end)) })
            if (isSet) {
                FilterChip(selected = false, onClick = { onSelectAfter(null); onDismiss() }, label = { Text(stringResource(R.string.player_sleep_off)) })
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

private val previewState = PlayerUiState(
    episodeId = EpisodeId(1),
    title = "第 128 回 春の新番組をふりかえる",
    programName = "週末ラジオ",
    isPlaying = true,
    positionMs = 754_000L,
    durationMs = 3_600_000L,
    hasPrevious = true,
    hasNext = true,
)

@Composable
private fun PlayerContentPreviewHost(
    state: PlayerUiState,
    played: Boolean = false,
    subtitle: UiText? = UiText.Plain("ラジオ局 · 山田太郎"),
    sleepTimerLabel: UiText? = null,
    swipeSeek: SwipeSeekState? = null,
    programImage: ProgramImage? = null,
    episodeImage: ByteArray? = null,
) {
    KikidameTheme {
        ProgramImagePreview {
            PlayerContent(
                state = state,
                played = played,
                subtitle = subtitle,
                programImage = programImage,
                episodeImage = episodeImage,
                speed = 1.25f,
                sleepTimerLabel = sleepTimerLabel,
                onBack = {},
                onSeek = {},
                onSetPlayed = {},
                onPrevious = {},
                onSeekBack = {},
                onTogglePlayPause = {},
                onSeekForward = {},
                onNext = {},
                onSetSpeed = {},
                onSetSleepTimer = {},
                onSetSleepTimerToEndOfEpisode = {},
                swipeSeek = swipeSeek ?: rememberSwipeSeekState(state.episodeId, state.positionMs, state.durationMs, onSeek = {}),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlayerContentPreview() {
    PlayerContentPreviewHost(previewState)
}

/** 番組の画像あり（#264）。タイトルの上の正方形の枠に切らずに全体が出る。プレビューの中では単色。 */
@Preview(showBackground = true)
@Composable
private fun PlayerContentWithImagePreview() {
    PlayerContentPreviewHost(previewState, programImage = previewProgramImage)
}

/** 回の画像あり（#283）。番組の画像より優先して出る。プレビューの中では単色（バイト列は読まれない）。 */
@Preview(showBackground = true)
@Composable
private fun PlayerContentWithEpisodeImagePreview() {
    PlayerContentPreviewHost(previewState, programImage = previewProgramImage, episodeImage = ByteArray(0))
}

/** 番組の画像あり・低い画面。画像はボタン類を押し出さない大きさまで縮む。 */
@Preview(showBackground = true, heightDp = 560)
@Composable
private fun PlayerContentWithImageShortScreenPreview() {
    PlayerContentPreviewHost(previewState, programImage = previewProgramImage)
}

/** 再生済み・一時停止中・スリープタイマーを設定中。 */
@Preview(showBackground = true)
@Composable
private fun PlayerContentPlayedPreview() {
    PlayerContentPreviewHost(
        previewState.copy(isPlaying = false, hasNext = false),
        played = true,
        sleepTimerLabel = UiText.Plain("14:59"),
    )
}

/** 読み込み中（尺が分からない。シークバーは無効で、スワイプも始まらない）。サブタイトルの行は 1 行分の場所だけ取る。 */
@Preview(showBackground = true)
@Composable
private fun PlayerContentUnknownDurationPreview() {
    PlayerContentPreviewHost(
        previewState.copy(isPlaying = false, positionMs = 0L, durationMs = 0L, hasPrevious = false),
        subtitle = null,
    )
}

/** チャプターのある回（#348）。題の行に今のチャプターの題、4 列目に「チャプター」。 */
@Preview(showBackground = true)
@Composable
private fun PlayerContentWithChaptersPreview() {
    PlayerContentPreviewHost(
        previewState.copy(chapters = listOf(EpisodeChapter(0, "オープニング"), EpisodeChapter(600_000, "ふつおたのコーナー"), EpisodeChapter(2_400_000, "エンディング"))),
    )
}

/** 再生できなかった。 */
@Preview(showBackground = true)
@Composable
private fun PlayerContentErrorPreview() {
    PlayerContentPreviewHost(previewState.copy(isPlaying = false, error = UiText.Plain("再生できませんでした")))
}

/** スワイプ中（右へ 70dp = +14 秒。差分と目標位置を出し、シークバーも目標位置を指す）。 */
@Preview(showBackground = true)
@Composable
private fun PlayerContentSwipingPreview() {
    val swipeSeek = remember {
        SwipeSeekState().apply {
            positionMs = previewState.positionMs
            durationMs = previewState.durationMs
            start(x = 0.5f, y = 0.5f, width = 1f, height = 1f)
            drag(70f)
        }
    }
    PlayerContentPreviewHost(previewState, swipeSeek = swipeSeek)
}
