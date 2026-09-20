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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.tseki.kikidame.BuildConfig
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.ui.SectionTitle
import dev.tseki.kikidame.ui.ValueRow
import dev.tseki.kikidame.ui.resolve
import dev.tseki.kikidame.ui.toDateTimeText
import dev.tseki.kikidame.ui.toText

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onChangeLibrary: () -> Unit,
    onOpenLicenses: () -> Unit,
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

    val context = LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it.resolve(context))
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
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
            SectionTitle(stringResource(R.string.settings_section_server))
            ValueRow("URL", current?.serverUrl ?: stringResource(R.string.settings_not_connected))
            ValueRow(stringResource(R.string.settings_user), current?.userName ?: "-")
            ValueRow(
                stringResource(R.string.settings_library),
                (s as? SessionState.Ready)?.library?.name ?: stringResource(R.string.settings_library_none),
                modifier = Modifier.clickable(enabled = current != null, onClick = onChangeLibrary),
                supporting = stringResource(R.string.settings_library_tap),
            )
            ValueRow(stringResource(R.string.settings_last_sync), (s as? SessionState.Ready)?.lastFetchedAt?.toDateTimeText() ?: "-")
            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_download))
            // 手元のファイルの合計（#42）。保持ルールや固定を調整する動機は容量なので、見えるようにする
            ValueRow(stringResource(R.string.settings_storage), localStorage?.toText()?.resolve() ?: "-")
            ListItem(
                modifier = Modifier.clickable { viewModel.setWifiOnly(!wifiOnly) },
                headlineContent = { Text(stringResource(R.string.settings_wifi_only)) },
                supportingContent = { Text(stringResource(R.string.settings_wifi_only_description)) },
                trailingContent = { Switch(checked = wifiOnly, onCheckedChange = viewModel::setWifiOnly) },
            )
            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_display))
            // テーマ（#62）。ラベル上・選択肢下。選んだ瞬間に MainActivity 側で切り替わる（再起動不要）
            ListItem(
                overlineContent = { Text(stringResource(R.string.settings_theme)) },
                headlineContent = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        for (choice in ThemeMode.entries) {
                            FilterChip(
                                selected = themeMode == choice,
                                onClick = { viewModel.setThemeMode(choice) },
                                enabled = themeMode != null,
                                label = { Text(stringResource(choice.label())) },
                            )
                        }
                    }
                },
            )
            // 言語（#104）。切替は OS のアプリ別言語設定（Android 13+）に任せ、ここはそこへのリンクだけ（ADR 0009）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ListItem(
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    },
                    headlineContent = { Text(stringResource(R.string.settings_language)) },
                    supportingContent = { Text(stringResource(R.string.settings_language_description)) },
                )
            }
            HorizontalDivider()
            // 見出しが無いと「表示」の続きに見えるので群にする（#62 のレビュー指摘）
            SectionTitle(stringResource(R.string.settings_section_account))
            ListItem(
                modifier = Modifier.clickable(enabled = current != null) { confirmSignOut = true },
                headlineContent = { Text(stringResource(R.string.settings_sign_out)) },
                supportingContent = { Text(stringResource(R.string.settings_sign_out_description)) },
            )
            ListItem(
                modifier = Modifier.clickable { confirmReset = true },
                headlineContent = { Text(stringResource(R.string.settings_connect_elsewhere), color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text(stringResource(R.string.settings_connect_elsewhere_description)) },
            )
            HorizontalDivider()
            // ライセンス（#91）。MPL-2.0 で公開し、LGPL-3.0 の jellyfin-sdk-kotlin を同梱しているので、出どころと依存の一覧をここに置く
            SectionTitle(stringResource(R.string.settings_section_about))
            ValueRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
            val uriHandler = LocalUriHandler.current
            ValueRow(
                stringResource(R.string.settings_license),
                "MPL-2.0",
                modifier = Modifier.clickable { uriHandler.openUri(SOURCE_URL) },
                supporting = stringResource(R.string.settings_license_description),
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onOpenLicenses),
                headlineContent = { Text(stringResource(R.string.settings_open_source_licenses)) },
                supportingContent = { Text(stringResource(R.string.settings_open_source_licenses_description)) },
            )
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.settings_sign_out_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_text)) },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; viewModel.signOut() }) { Text(stringResource(R.string.settings_sign_out)) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_text)) },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; viewModel.resetAndConnectElsewhere() }) {
                    Text(stringResource(R.string.settings_reset_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
private fun ThemeMode.label(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.DARK -> R.string.settings_theme_dark
    ThemeMode.LIGHT -> R.string.settings_theme_light
}

private const val SOURCE_URL = "https://github.com/t-seki/kikidame"
