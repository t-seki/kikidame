package dev.tseki.kikidame.ui.episodes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.playback.NowPlayingState
import dev.tseki.kikidame.ui.UiText
import dev.tseki.kikidame.ui.resolve
import kotlin.time.Duration

/** タイトル前の印の大きさと、印とタイトルの間隔。印の無い行の枠と補足行の字下げにも使う。 */
private val MARK_SIZE = 14.dp
private val MARK_GAP = 4.dp

/**
 * 各回の行。各回一覧と「続きから」（#151）が共通で使う（#153）。
 * 右端のアイコンは状態を表し、タップで最も自然な 1 操作をする（[trailing] で画面ごとに渡す）。
 * 残りの操作は長押しのボトムシート（[EpisodeActionsSheet]）。手元に無い回はタップで再生画面へ行かない。
 * タイトルの前に印を出す: 固定（ダウンロード済みで固定された回）のピンと、聴いている回（[nowPlaying] がこの回。背景も変える）の
 * 再生中／一時停止。両方あれば並べる。印の無い行にも同じ幅を確保し、タイトル（1 行）と補足行の開始位置を揃える。
 * [supportingText] は 2 行目（画面ごとに違う）。その下に再生位置のバーを出す。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EpisodeRow(
    item: EpisodeWithState,
    nowPlaying: NowPlayingState?,
    supportingText: UiText,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val resume = item.resumePosition
    val runtime = item.episode.runtime
    val playable = item.isPlayable
    val local = item.localFile
    val state = local?.state
    ListItem(
        modifier = Modifier
            .combinedClickable(onClick = { if (playable) onClick() }, onLongClick = onLongClick)
            .alpha(if (playable || state != null) 1f else 0.5f),
        colors = ListItemDefaults.colors(
            containerColor = if (nowPlaying != null) MaterialTheme.colorScheme.secondaryContainer else ListItemDefaults.containerColor,
        ),
        headlineContent = {
            // 印（固定・再生中／一時停止）はタイトルの前に置くが、印の無い行にも同じ幅の枠を確保してタイトルの開始位置を揃える（#56）。
            // 固定と聴いている回は独立した状態なので両方出す（両方ある行だけ 1 つ分右にずれるが、背景色で目立つ 1 行なので許容）
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pinned = local?.pinned == true && state == DownloadState.DONE
                if (pinned) {
                    Icon(Icons.Filled.PushPin, contentDescription = stringResource(R.string.episode_list_pinned), Modifier.size(MARK_SIZE))
                    Spacer(Modifier.width(MARK_GAP))
                }
                when {
                    nowPlaying != null && nowPlaying.isPlaying -> {
                        Icon(Icons.Filled.GraphicEq, contentDescription = stringResource(R.string.episode_list_now_playing_playing), Modifier.size(MARK_SIZE), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(MARK_GAP))
                    }
                    nowPlaying != null -> {
                        Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.episode_list_now_playing_paused), Modifier.size(MARK_SIZE), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(MARK_GAP))
                    }
                    !pinned -> Spacer(Modifier.width(MARK_SIZE + MARK_GAP)) // 印が無い行の枠
                }
                Text(item.episode.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        supportingContent = {
            // 補足行もタイトルと同じ位置から始める（枠＋間隔ぶんを空ける）
            Column(Modifier.padding(start = MARK_SIZE + MARK_GAP)) {
                Text(supportingText.resolve())
                if (playable && resume != null && runtime > Duration.ZERO) {
                    LinearProgressIndicator(
                        progress = { (resume / runtime).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        },
        trailingContent = trailing,
    )
}

/**
 * 長押しのシート（#153: 各回一覧と「続きから」で共通）。ダウンロード（[onDownload]）は null なら出さない
 * （「続きから」の回は必ず手元にある）。[syncEnabled] は番組が同期対象か（固定を外す操作の条件）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeActionsSheet(
    item: EpisodeWithState,
    syncEnabled: Boolean,
    onDismiss: () -> Unit,
    onDownload: (() -> Unit)?,
    onUnpin: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
) {
    val local = item.localFile
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(item.episode.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (onDownload != null && local == null && item.episode.serverItemId != null) {
            SheetAction(Icons.Filled.Download, stringResource(R.string.episode_list_action_download)) { onDownload(); onDismiss() }
        }
        if (local?.state == DownloadState.DONE && local.pinned && syncEnabled) {
            SheetAction(Icons.Outlined.PushPin, stringResource(R.string.episode_list_action_unpin)) { onUnpin(); onDismiss() }
        }
        if (local != null) {
            val label = stringResource(if (item.episode.serverItemId != null) R.string.episode_list_action_delete_file else R.string.episode_list_action_delete_episode)
            SheetAction(Icons.Filled.Delete, label) { onDelete(); onDismiss() }
        }
        SheetAction(Icons.Outlined.Info, stringResource(R.string.episode_list_action_details)) { onDetails() }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
    )
}
