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
import dev.tseki.kikidame.BuildConfig
import dev.tseki.kikidame.R

/**
 * SMB の選択肢を出すか。走査の作り直しが終わるまで（#198 の追加の決定）、debug 版だけで出し、release 版では Jellyfin だけを出す。
 * 出し分けを外すのは、大きな共有で初回の全走査が実機で通ると確かめたとき。
 */
internal fun isSmbSelectable(debugBuild: Boolean): Boolean = debugBuild

/**
 * 初回の取得元の選択画面（#198、epic #195 の決定 3）。「Jellyfin に接続」と「NAS の共有フォルダ（SMB）」。SMB は debug 版だけ（[isSmbSelectable]）。
 * 端末のフォルダ（SAF）は #199 で足す。選ぶだけで何も保存せず、次の接続画面へ進む。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePickScreen(
    onPickJellyfin: () -> Unit,
    onPickSmb: () -> Unit,
    showSmb: Boolean = isSmbSelectable(BuildConfig.DEBUG),
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
            if (showSmb) {
                SourceCard(
                    title = stringResource(R.string.source_pick_smb),
                    description = stringResource(R.string.source_pick_smb_description),
                    onClick = onPickSmb,
                )
            }
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
