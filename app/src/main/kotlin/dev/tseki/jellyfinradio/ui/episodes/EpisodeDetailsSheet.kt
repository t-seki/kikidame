package dev.tseki.jellyfinradio.ui.episodes

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.tseki.jellyfinradio.domain.EpisodeWithState
import kotlinx.coroutines.launch

/**
 * 各回の詳細（#43）。利用者向けの 3 群を出し、末尾の「技術的な詳細」を展開すると ID・生の値・パスが等幅で並ぶ。
 * 展開した行は長押しでコピー（Android 13 以降は OS がコピーを通知するのでスナックバーは出さない）。
 * 画面は増やさない（番組の詳細画面を作らないのと同じ判断）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun EpisodeDetailsSheet(item: EpisodeWithState, onDismiss: () -> Unit) {
    // 開き直すたびに畳んだ状態から
    var showTechnical by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn {
            item {
                Text(item.episode.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
            for (section in EpisodeDetails.sections(item)) {
                item { SectionTitle(section.title) }
                items(section.rows) { row ->
                    ListItem(headlineContent = { Text(row.value) }, supportingContent = { Text(row.label) })
                }
            }
            item {
                ListItem(
                    modifier = Modifier.combinedClickable { showTechnical = !showTechnical },
                    headlineContent = { Text("技術的な詳細") },
                    supportingContent = { Text("ID・サーバの生の値・保存先など。長押しでコピー") },
                    trailingContent = {
                        Icon(if (showTechnical) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (showTechnical) "畳む" else "展開")
                    },
                )
            }
            if (showTechnical) {
                items(EpisodeDetails.technicalRows(item)) { row ->
                    ListItem(
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(row.label, row.value))) }
                            },
                        ),
                        headlineContent = { Text(row.value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) },
                        supportingContent = { Text(row.label) },
                    )
                }
            }
            item { Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
    )
}
