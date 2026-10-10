package dev.tseki.kikidame.ui.connect

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.kikidame.R
import dev.tseki.kikidame.ui.resolve
import dev.tseki.kikidame.ui.source.ChangeSourceButton

/**
 * 端末のフォルダ（SAF、#199 の決定 8）の説明と選択。3 段の構成の例と、内部ストレージの直下や Download そのものは選べないこと、
 * コピーするので容量が二重になることと、フォルダのファイルを消すと手元のコピーも消えること（#199 の「決定の追記」）を示し、
 * 「フォルダを選ぶ」で OS のフォルダ選択（`ACTION_OPEN_DOCUMENT_TREE`）を開く。選んだフォルダの直下が読めたら保存し、
 * セッション状態が変わって NavHost が番組一覧へ導く。アクセスが切れてログアウトした後は、選び直しの案内を出す（決定 5）。
 * [onBack] は取得元の選択から来たときだけ渡す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceFolderScreen(
    onBack: (() -> Unit)?,
    onSourceChanged: () -> Unit,
    viewModel: DeviceFolderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.onPicked(uri?.toString())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.device_folder_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                    }
                },
            )
        },
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
            state.lostFolderName?.let {
                Text(stringResource(R.string.device_folder_lost, it), color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.device_folder_description), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.device_folder_example),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(stringResource(R.string.device_folder_pick_hint), style = MaterialTheme.typography.bodyMedium)
            // コピーしてから聴く形（#199 の決定 1）から来る制約（#199 の「決定の追記」: 容量が二重になる、フォルダのファイルは消さずに置いておく）
            for (hint in listOf(R.string.device_folder_copy_hint, R.string.device_folder_storage_hint, R.string.device_folder_keep_hint)) {
                Text(
                    stringResource(hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
            Button(onClick = { pickFolder.launch(null) }, enabled = !state.isSubmitting, modifier = Modifier.fillMaxWidth()) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.device_folder_pick))
                }
            }
            // ログアウトした後は取得元を選び直す入口が他に無い。取得元の選択から来たときは戻るで選び直せる
            if (onBack == null) ChangeSourceButton(onConfirm = { viewModel.changeSource(onSourceChanged) })
        }
    }
}
