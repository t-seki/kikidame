package dev.tseki.kikidame.ui.connect

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.kikidame.R
import dev.tseki.kikidame.ui.resolve

/** Jellyfin のサーバ URL・ユーザー名・パスワードでログインする。成功するとセッション状態が変わり、NavHost が次の画面へ導く。[onBack] は取得元の選択から来たときだけ渡す。 */
@Composable
fun ConnectScreen(
    onBack: (() -> Unit)? = null,
    onSourceChanged: () -> Unit,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SourceConnectScaffold(stringResource(R.string.connect_title), onBack, onSourceChanged) {
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = viewModel::onServerUrlChange,
            label = { Text(stringResource(R.string.connect_server)) },
            placeholder = { Text("jellyfin.example.net") },
            supportingText = { Text(stringResource(R.string.connect_server_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
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
        state.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
        SourceConnectSubmitButton(
            text = stringResource(R.string.connect_submit),
            isSubmitting = state.isSubmitting,
            enabled = state.canSubmit,
            onClick = viewModel::submit,
        )
    }
}
