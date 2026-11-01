package dev.tseki.kikidame.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.tseki.kikidame.R
import dev.tseki.kikidame.ui.source.ChangeSourceButton
import dev.tseki.kikidame.ui.source.ChangeSourceViewModel

/**
 * 取得元の接続画面（Jellyfin・SMB・端末のフォルダ）の枠。トップバー（[onBack] があるときだけ ←）、スクロールする縦の並び、
 * 末尾の「取得元を変える」。[onBack] は取得元の選択から来たときだけ渡す。
 * ログアウトした後（[onBack] が無い）は取得元を選び直す入口が他に無いので「取得元を変える」を置き、手元を全部消した後に
 * [onSourceChanged]（初回の取得元の選択画面へ戻る）を呼ぶ。取得元の選択から来たときは戻るで選び直せるので置かない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceConnectScaffold(
    title: String,
    onBack: (() -> Unit)?,
    onSourceChanged: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
            content()
            if (onBack == null) {
                val changer: ChangeSourceViewModel = hiltViewModel()
                ChangeSourceButton(onConfirm = { changer.changeSource(onSourceChanged) })
            }
        }
    }
}

/** 接続画面の送信ボタン。送信中は文言の代わりにクルクルを出す。 */
@Composable
fun SourceConnectSubmitButton(text: String, isSubmitting: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        if (isSubmitting) {
            CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
        } else {
            Text(text)
        }
    }
}
