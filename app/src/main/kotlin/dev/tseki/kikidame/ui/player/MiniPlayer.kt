package dev.tseki.kikidame.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.ProgramImage
import dev.tseki.kikidame.playback.NowPlayingState
import dev.tseki.kikidame.ui.ProgramImagePreview
import dev.tseki.kikidame.ui.ProgramImageView
import dev.tseki.kikidame.ui.previewProgramImage
import dev.tseki.kikidame.ui.theme.KikidameTheme
import androidx.compose.ui.tooling.preview.Preview

/**
 * 一覧の下に出す「聴いている回」のバー。左に番組の画像（あれば、#289）、番組名 / 回タイトルと再生／一時停止、上端に再生位置の線（#59）を持ち、
 * バーのタップで再生画面へ行く。聴いている回が無ければ何も描かない。
 * `Scaffold(bottomBar)` に置く前提で、ナビゲーションバーの inset はここで取る。
 */
@Composable
fun MiniPlayer(onClick: (EpisodeId) -> Unit, viewModel: MiniPlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state ?: return
    val programImage by viewModel.programImage.collectAsStateWithLifecycle()
    MiniPlayerBar(state = current, programImage = programImage, onClick = { onClick(current.episodeId) }, onTogglePlayPause = viewModel::togglePlayPause)
}

@Composable
internal fun MiniPlayerBar(state: NowPlayingState, programImage: ProgramImage?, onClick: () -> Unit, onTogglePlayPause: () -> Unit) {
    // 面は surfaceContainer に primary を少し混ぜたアンバー系（#59、docs/ui.md）。primaryContainer だと濃すぎる
    val tint = lerp(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.primary, 0.18f)
    // 混ぜた色は contentColorFor で引けないので、文字色は明示する
    Surface(color = tint, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
            // 上端の再生位置の線（#59）。尺が分からないときは出さない
            if (state.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    trackColor = Color.Transparent,
                    strokeCap = StrokeCap.Butt,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 聴いている回の番組の画像（#289）。無い番組では場所を空けず、今の見た目のまま
                if (programImage != null) {
                    ProgramImageView(programImage, Modifier.padding(end = 12.dp).size(40.dp))
                }
                Column(Modifier.weight(1f)) {
                    state.programName?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(state.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onTogglePlayPause) {
                    if (state.isPlaying) {
                        Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.common_pause))
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.common_play))
                    }
                }
            }
        }
    }
}

private val previewState = NowPlayingState(
    episodeId = EpisodeId(1),
    title = "第 5 回",
    programName = "サンプル番組",
    isPlaying = true,
    positionMs = 600_000,
    durationMs = 2_700_000,
)

@Preview(showBackground = true)
@Composable
private fun MiniPlayerBarPreview() {
    KikidameTheme { MiniPlayerBar(previewState, programImage = null, onClick = {}, onTogglePlayPause = {}) }
}

@Preview(showBackground = true)
@Composable
private fun MiniPlayerBarWithImagePreview() {
    KikidameTheme {
        ProgramImagePreview { MiniPlayerBar(previewState, programImage = previewProgramImage, onClick = {}, onTogglePlayPause = {}) }
    }
}
