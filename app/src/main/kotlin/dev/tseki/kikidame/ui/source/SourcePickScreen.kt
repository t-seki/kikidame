package dev.tseki.kikidame.ui.source

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.tseki.kikidame.LocalNetworkPermission
import dev.tseki.kikidame.R

/**
 * 初回の取得元の選択画面（#198、epic #195 の決定 3）。「Jellyfin に接続」「NAS の共有フォルダ（SMB）」「端末のフォルダ」（#199）。
 * SMB は、走査を作り直すまで debug 版だけで出していた（#198 の追加の決定）。#209 で作り直したので、release 版でも出す。
 * 選ぶだけで何も保存せず、次の接続画面（端末のフォルダは説明の画面）へ進む。
 * Jellyfin か SMB を選んだら、ローカルネットワークの権限が要って無ければ要求してから進む（#199 の決定 7。拒否されても進む。
 * 届かなかったときは接続画面が権限の案内を出す）。端末のフォルダでは要求しない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePickScreen(
    onPickJellyfin: () -> Unit,
    onPickSmb: () -> Unit,
    onPickDeviceFolder: () -> Unit,
) {
    val context = LocalContext.current
    val requestLocalNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒否されても進む */ }
    // 権限のダイアログを出し、その下で次の画面へ進める（接続を試すのは、ダイアログを閉じてから利用者が押したとき）
    val withLocalNetwork = { next: () -> Unit ->
        if (LocalNetworkPermission.isRequiredAndMissing(context)) requestLocalNetwork.launch(LocalNetworkPermission.NAME)
        next()
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.source_pick_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.source_pick_description), style = MaterialTheme.typography.bodyMedium)
            SourceCard(
                title = stringResource(R.string.source_pick_jellyfin),
                description = stringResource(R.string.source_pick_jellyfin_description),
                onClick = { withLocalNetwork(onPickJellyfin) },
            )
            SourceCard(
                title = stringResource(R.string.source_pick_smb),
                description = stringResource(R.string.source_pick_smb_description),
                onClick = { withLocalNetwork(onPickSmb) },
            )
            SourceCard(
                title = stringResource(R.string.source_pick_device),
                description = stringResource(R.string.source_pick_device_description),
                onClick = onPickDeviceFolder,
            )
        }
    }
}

@Composable
private fun SourceCard(title: String, description: String, onClick: () -> Unit) {
    Card(modifier = Modifier.clickable(onClick = onClick)) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(description) },
        )
    }
}
