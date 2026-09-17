package dev.tseki.jellyfinradio.ui.settings

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
import dev.tseki.jellyfinradio.BuildConfig
import dev.tseki.jellyfinradio.domain.SessionState
import dev.tseki.jellyfinradio.ui.toDateTimeText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onChangeLibrary: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
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
            Text("サーバ", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
            ListItem(headlineContent = { Text(current?.serverUrl ?: "未接続") }, supportingContent = { Text("URL") })
            ListItem(headlineContent = { Text(current?.userName ?: "-") }, supportingContent = { Text("ユーザー") })
            ListItem(
                modifier = Modifier.clickable(enabled = current != null, onClick = onChangeLibrary),
                headlineContent = { Text((s as? SessionState.Ready)?.library?.name ?: "未選択") },
                supportingContent = { Text("ライブラリ（タップで選び直す）") },
            )
            ListItem(
                headlineContent = { Text((s as? SessionState.Ready)?.lastFetchedAt?.toDateTimeText() ?: "-") },
                supportingContent = { Text("最終取得") },
            )
            HorizontalDivider()
            Text("ダウンロード", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
            ListItem(
                modifier = Modifier.clickable { viewModel.setWifiOnly(!wifiOnly) },
                headlineContent = { Text("Wi-Fi のみ") },
                supportingContent = { Text("オフにするとモバイル回線でもダウンロードします") },
                trailingContent = { Switch(checked = wifiOnly, onCheckedChange = viewModel::setWifiOnly) },
            )
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable(enabled = current != null) { confirmSignOut = true },
                headlineContent = { Text("ログアウト") },
                supportingContent = { Text("認証情報だけを消します。手元の番組・各回・再生位置は残ります") },
            )
            ListItem(
                modifier = Modifier.clickable { confirmReset = true },
                headlineContent = { Text("別のサーバに接続", color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text("手元の番組・各回・再生位置をすべて消してから接続画面へ。音声ファイルは消しません") },
            )

            if (BuildConfig.DEBUG) {
                HorizontalDivider()
                Text("デバッグ", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
                ListItem(
                    modifier = Modifier.clickable(onClick = viewModel::runSeed),
                    headlineContent = { Text("シード") },
                    supportingContent = { Text("${viewModel.seedRoot}/<放送局>/<番組>/ を走査して手元のファイルを取り込みます") },
                )
            }
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
            text = { Text("番組・各回・再生位置・再生済みがすべて消えます。この操作は取り消せません。音声ファイルは消しません。") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; viewModel.resetAndConnectElsewhere() }) {
                    Text("消して接続画面へ", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("キャンセル") } },
        )
    }
}
