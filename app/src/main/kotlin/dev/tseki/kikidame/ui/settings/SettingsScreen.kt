package dev.tseki.kikidame.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.FilterChip
import dev.tseki.kikidame.domain.ThemeMode
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.ui.SectionTitle
import dev.tseki.kikidame.ui.ValueRow
import dev.tseki.kikidame.ui.toDateTimeText
import dev.tseki.kikidame.ui.toText

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onChangeLibrary: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val localStorage by viewModel.localStorage.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            val s = session
            val current = when (s) {
                is SessionState.Ready -> s.session
                is SessionState.NeedsLibrary -> s.session
                else -> null
            }
            // 値を見せる行はラベル上・値下（#58、各回の詳細と同じ向き）。操作の行は操作名が上で説明が下
            SectionTitle("サーバ")
            ValueRow("URL", current?.serverUrl ?: "未接続")
            ValueRow("ユーザー", current?.userName ?: "-")
            ValueRow(
                "ライブラリ",
                (s as? SessionState.Ready)?.library?.name ?: "未選択",
                modifier = Modifier.clickable(enabled = current != null, onClick = onChangeLibrary),
                supporting = "タップで選び直す",
            )
            ValueRow("最終同期", (s as? SessionState.Ready)?.lastFetchedAt?.toDateTimeText() ?: "-")
            HorizontalDivider()
            SectionTitle("ダウンロード")
            // 手元のファイルの合計（#42）。保持ルールや固定を調整する動機は容量なので、見えるようにする
            ValueRow("手元のファイル", localStorage?.toText() ?: "-")
            ListItem(
                modifier = Modifier.clickable { viewModel.setWifiOnly(!wifiOnly) },
                headlineContent = { Text("Wi-Fi のみ") },
                supportingContent = { Text("オフにするとモバイル回線でもダウンロードします") },
                trailingContent = { Switch(checked = wifiOnly, onCheckedChange = viewModel::setWifiOnly) },
            )
            HorizontalDivider()
            SectionTitle("表示")
            // テーマ（#62）。ラベル上・選択肢下。選んだ瞬間に MainActivity 側で切り替わる（再起動不要）
            ListItem(
                overlineContent = { Text("テーマ") },
                headlineContent = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        for (choice in ThemeMode.entries) {
                            FilterChip(
                                selected = themeMode == choice,
                                onClick = { viewModel.setThemeMode(choice) },
                                enabled = themeMode != null,
                                label = { Text(choice.label()) },
                            )
                        }
                    }
                },
            )
            HorizontalDivider()
            // 見出しが無いと「表示」の続きに見えるので群にする（#62 のレビュー指摘）
            SectionTitle("アカウント")
            ListItem(
                modifier = Modifier.clickable(enabled = current != null) { confirmSignOut = true },
                headlineContent = { Text("ログアウト") },
                supportingContent = { Text("認証情報だけを消します。手元の番組・各回・再生位置は残ります") },
            )
            ListItem(
                modifier = Modifier.clickable { confirmReset = true },
                headlineContent = { Text("別のサーバに接続", color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text("手元の番組・各回・再生位置・音声ファイルをすべて消してから接続画面へ") },
            )
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("ログアウトしますか？") },
            text = { Text("手元のデータは残ります。次回はもう一度ログインが必要です。") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; viewModel.signOut() }) { Text("ログアウト") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("キャンセル") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("手元のデータを消して別のサーバに接続しますか？") },
            text = { Text("番組・各回・再生位置・再生済み・音声ファイルがすべて消えます。この操作は取り消せません。") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; viewModel.resetAndConnectElsewhere() }) {
                    Text("消して接続画面へ", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("キャンセル") } },
        )
    }
}
private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "システム"
    ThemeMode.DARK -> "ダーク"
    ThemeMode.LIGHT -> "ライト"
}
