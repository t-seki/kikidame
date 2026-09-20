package dev.tseki.kikidame.ui.episodes
import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.kikidame.ui.SectionTitle
import dev.tseki.kikidame.ui.ValueRow
import kotlinx.coroutines.launch
/**
 * 各回の詳細（#43）。利用者向けの 3 群を出し、末尾の「技術的な詳細」を展開すると ID・記録の生の値・パスが等幅で並ぶ。
 * 展開した行は長押しでコピー。Android 13 以降は OS がコピーを通知するので何も出さず、それより前（minSdk 31）は Toast で知らせる。
 * 当初はボトムシートだったが、スクロールする長い一覧はシートのドラッグと取り合いになるので画面にした（2026-09-19）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EpisodeDetailsScreen(onBack: () -> Unit, viewModel: EpisodeDetailsViewModel = hiltViewModel()) {
    val item by viewModel.item.collectAsStateWithLifecycle()
    val program by viewModel.program.collectAsStateWithLifecycle()
    // 開き直すたびに畳んだ状態から（回転では保つ）
    var showTechnical by rememberSaveable { mutableStateOf(false) }
    // 見ている間にその回が消えたら（保持ルールの削除・番組ごと消した）一覧へ戻る。読み込み前の null とは区別する
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(item) {
        if (item != null) loaded = true else if (loaded) onBack()
    }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.episode?.title ?: "各回の詳細") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        val current = item ?: return@Scaffold
        // 番組も読めてから出す（先に各回だけ出すと放送局の行が一瞬「不明」になる）。各回があれば番組は必ずある（FK）
        val currentProgram = program ?: return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            for (section in EpisodeDetails.sections(current, currentProgram)) {
                item { SectionTitle(section.title) }
                items(section.rows) { row -> ValueRow(row.label, row.value) }
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { showTechnical = !showTechnical },
                    headlineContent = { Text("技術的な詳細") },
                    supportingContent = { Text("ID・記録の生の値・保存先など。長押しでコピー") },
                    trailingContent = {
                        Icon(if (showTechnical) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (showTechnical) "畳む" else "展開")
                    },
                )
            }
            if (showTechnical) {
                items(EpisodeDetails.technicalRows(current)) { row ->
                    ListItem(
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(row.label, row.value)))
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                        Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        ),
                        overlineContent = { Text(row.label) },
                        headlineContent = { Text(row.value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) },
                    )
                }
            }
        }
    }
}
