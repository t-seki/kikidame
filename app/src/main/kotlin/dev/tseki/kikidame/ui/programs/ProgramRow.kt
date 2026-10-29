package dev.tseki.kikidame.ui.programs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.ProgramSummary
import dev.tseki.kikidame.domain.PublishedAt
import dev.tseki.kikidame.text.UiText
import dev.tseki.kikidame.ui.ProgramImageView
import dev.tseki.kikidame.ui.resolve
import dev.tseki.kikidame.ui.toLatestDateText
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.todayIn

/**
 * 左端の ★ でよく聴くを切り替える。右端は同期対象／消失の状態表示。
 * ★ の右、番組名の左に 48dp の正方形で番組の画像（ADR 0009）を置く。[reserveImage] が true なら、画像の無い番組でも
 * 48dp を空けて（何も描かない）文字の位置を全行でそろえる。一覧の中に画像を持つ番組が 1 つも無いときは、呼び出し側が false にして詰める。
 *
 * M3 の `ListItem` は補足が 2 行に折り返して 3 行になると左右の要素を上に寄せる（`ListItem.kt` の `place()`）。
 * 番組の行は行の高さに関わらず左右を真ん中に置く方針なので、`ListItem` は使わず [Row] で組む。
 * 左右は本文の高さが決まる前に測られるので、中身を `fillMaxHeight` で中央寄せにしても `ListItem` では真ん中に置けない。
 * 余白・最小の高さ・文字のスタイルと色は、`ListItem`（material3 1.4.0）の 1〜2 行のときの値に合わせてある。
 * 3 行になっても上下 8dp・最小 72dp のままにする（`ListItem` の 3 行の既定は上下 12dp・最小 88dp）。
 */
@Composable
internal fun ProgramRow(summary: ProgramSummary, reserveImage: Boolean, onClick: () -> Unit, onToggleStarred: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.padding(end = 16.dp)) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    IconButton(onClick = onToggleStarred) {
                        if (summary.program.starred) {
                            Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.program_list_star_remove), tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Outlined.StarOutline, contentDescription = stringResource(R.string.program_list_star_add))
                        }
                    }
                }
            }
            val image = summary.program.image
            if (image != null) {
                ProgramImageView(image, Modifier.padding(end = 16.dp).size(48.dp))
            } else if (reserveImage) {
                Spacer(Modifier.padding(end = 16.dp).size(48.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(summary.program.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    summary.toSupportingText().resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.padding(start = 16.dp)) {
                when {
                    summary.program.isGone -> Icon(Icons.Filled.CloudOff, contentDescription = stringResource(R.string.common_program_gone), tint = MaterialTheme.colorScheme.error)
                    summary.program.syncEnabled -> Icon(Icons.Filled.Sync, contentDescription = stringResource(R.string.program_list_sync_enabled), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * 行の補足: `配信元 · 未再生 N / 手元 L · 全 E 回 · 最新 MM-DD`。
 * 未再生 0 なら「未再生 N /」を省き、手元 = 全なら「全 E 回」を「回」に畳む（「/」が 2 つ並ばないよう「全」の前は「·」）。
 * 配信元が無い・各回が無い（最新なし）ならその部分を省き、消失なら末尾に「取得元に見つかりません」を足す。
 */
internal fun ProgramSummary.toSupportingText(today: LocalDate = Clock.System.todayIn(PublishedAt.ZONE)): UiText {
    val unplayed = unplayedLocalCount > 0
    val all = localEpisodeCount == episodeCount
    val count = when {
        unplayed && all -> UiText.Res(R.string.program_list_unplayed_on_device, unplayedLocalCount, localEpisodeCount)
        unplayed -> UiText.Res(R.string.program_list_unplayed_on_device_of_total, unplayedLocalCount, localEpisodeCount, episodeCount)
        all -> UiText.Res(R.string.program_list_on_device, localEpisodeCount)
        else -> UiText.Res(R.string.program_list_on_device_of_total, localEpisodeCount, episodeCount)
    }
    val latest = latestPublishedAt?.let { UiText.Res(R.string.program_list_latest, it.toLatestDateText(today)) }
    val gone = if (program.isGone) UiText.Res(R.string.common_program_gone) else null
    return UiText.Joined(listOfNotNull(program.publisherName?.let(UiText::Plain), count, latest, gone), UiText.Plain(" · "))
}
