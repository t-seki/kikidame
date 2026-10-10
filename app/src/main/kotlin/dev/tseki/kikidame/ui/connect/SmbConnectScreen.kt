package dev.tseki.kikidame.ui.connect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.kikidame.R
import dev.tseki.kikidame.ui.resolve

/**
 * NAS の共有フォルダ（SMB）に接続する（#198）。ホスト・共有名・共有の中のパス・ユーザー名・パスワード。ゲスト接続も選べる。
 * 成功するとセッション状態が変わり、NavHost が番組一覧へ導く。[onBack] は取得元の選択から来たときだけ渡す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbConnectScreen(
    onBack: (() -> Unit)?,
    viewModel: SmbConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.smb_connect_title)) },
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
            OutlinedTextField(
                value = state.host,
                onValueChange = viewModel::onHostChange,
                label = { Text(stringResource(R.string.smb_connect_host)) },
                placeholder = { Text("192.168.1.10") },
                supportingText = { Text(stringResource(R.string.smb_connect_host_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.share,
                onValueChange = viewModel::onShareChange,
                label = { Text(stringResource(R.string.smb_connect_share)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.path,
                onValueChange = viewModel::onPathChange,
                label = { Text(stringResource(R.string.smb_connect_path)) },
                supportingText = { Text(stringResource(R.string.smb_connect_path_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            ListItem(
                modifier = Modifier.clickable { viewModel.onGuestChange(!state.guest) },
                headlineContent = { Text(stringResource(R.string.smb_connect_guest)) },
                trailingContent = { Switch(checked = state.guest, onCheckedChange = viewModel::onGuestChange) },
            )
            if (!state.guest) {
                OutlinedTextField(
                    value = state.userName,
                    onValueChange = viewModel::onUserNameChange,
                    label = { Text(stringResource(R.string.connect_user_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    label = { Text(stringResource(R.string.connect_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
            Button(onClick = viewModel::submit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.connect_submit))
                }
            }
        }
    }
}
