package dev.tseki.kikidame.ui.source

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.tseki.kikidame.R

/**
 * 初回の取得元の選択画面（#198、epic #195 の決定 3）。「Jellyfin に接続」と「NAS の共有フォルダ（SMB）」。
 * SMB は、走査を作り直すまで debug 版だけで出していた（#198 の追加の決定）。#209 で作り直したので、release 版でも出す。
 * 端末のフォルダ（SAF）は #199 で足す。選ぶだけで何も保存せず、次の接続画面へ進む。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePickScreen(
    onPickJellyfin: () -> Unit,
    onPickSmb: () -> Unit,
) {
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
                onClick = onPickJellyfin,
            )
            SourceCard(
                title = stringResource(R.string.source_pick_smb),
                description = stringResource(R.string.source_pick_smb_description),
                onClick = onPickSmb,
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
