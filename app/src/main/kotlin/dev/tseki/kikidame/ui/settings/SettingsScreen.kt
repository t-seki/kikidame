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
import androidx.compose.ui.res.pluralStringResource
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
import dev.tseki.kikidame.domain.ConnectedSource
import dev.tseki.kikidame.domain.DeviceFolder
import dev.tseki.kikidame.domain.LocalStorageUsage
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.Session
import dev.tseki.kikidame.domain.SmbConnection
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.UnmatchedPrograms
import dev.tseki.kikidame.ui.theme.KikidameTheme
import androidx.compose.ui.tooling.preview.Preview
import kotlin.time.Instant
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.ui.SectionTitle
import dev.tseki.kikidame.ui.ValueRow
import dev.tseki.kikidame.ui.connect.displayPath
import dev.tseki.kikidame.ui.resolve
import dev.tseki.kikidame.ui.source.ChangeSourceConfirmDialog
import dev.tseki.kikidame.ui.toDateTimeText
import dev.tseki.kikidame.ui.toText

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onChangeLibrary: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenStorage: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val localStorage by viewModel.localStorage.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val unmatched by viewModel.unmatched.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val removePreview by viewModel.removePreview.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it.resolve(context))
            viewModel.consumeMessage()
        }
    }

    SettingsContent(
        session = session,
        wifiOnly = wifiOnly,
        localStorage = localStorage,
        themeMode = themeMode,
        unmatched = unmatched,
        isSyncing = isSyncing,
        removePreview = removePreview,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onChangeLibrary = onChangeLibrary,
        onOpenLicenses = onOpenLicenses,
        onOpenStorage = onOpenStorage,
        onRemoveUnmatched = viewModel::requestRemoveUnmatched,
        onConfirmRemoveUnmatched = viewModel::confirmRemoveUnmatched,
        onCancelRemoveUnmatched = viewModel::cancelRemoveUnmatched,
        onSetWifiOnly = viewModel::setWifiOnly,
        onSetThemeMode = viewModel::setThemeMode,
        onSignOut = viewModel::signOut,
        onResetAndConnectElsewhere = viewModel::resetAndConnectElsewhere,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SettingsContent(
    session: SessionState?,
    wifiOnly: Boolean,
    localStorage: LocalStorageUsage?,
    themeMode: ThemeMode?,
    unmatched: UnmatchedPrograms,
    isSyncing: Boolean,
    removePreview: UnmatchedPrograms?,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onChangeLibrary: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenStorage: () -> Unit,
    onRemoveUnmatched: () -> Unit,
    onConfirmRemoveUnmatched: () -> Unit,
    onCancelRemoveUnmatched: () -> Unit,
    onSetWifiOnly: (Boolean) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSignOut: () -> Unit,
    onResetAndConnectElsewhere: () -> Unit,
) {
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val context = LocalContext.current

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
            val connected = (s as? SessionState.Ready)?.source
            val jellyfin = connected as? ConnectedSource.Jellyfin
            val jellyfinSession = jellyfin?.session ?: (s as? SessionState.NeedsLibrary)?.session
            val smb = (connected as? ConnectedSource.Smb)?.connection
            val deviceFolder = (connected as? ConnectedSource.Device)?.folder
            val isConnected = jellyfinSession != null || smb != null || deviceFolder != null
            // 値を見せる行はラベル上・値下（#58、各回の詳細と同じ向き）。操作の行は操作名が上で説明が下
            // 今の取得元（種類と場所）を出す（#198）。取得元は同時に 1 つ
            SectionTitle(stringResource(R.string.settings_section_source))
            ValueRow(
                stringResource(R.string.settings_source),
                when {
                    smb != null -> stringResource(R.string.settings_source_smb)
                    deviceFolder != null -> stringResource(R.string.settings_source_device)
                    jellyfinSession != null -> stringResource(R.string.settings_source_jellyfin)
                    else -> stringResource(R.string.settings_not_connected)
                },
            )
            if (smb != null) {
                ValueRow(stringResource(R.string.settings_smb_host), smb.host)
                ValueRow(stringResource(R.string.settings_smb_share), smb.share)
                ValueRow(stringResource(R.string.settings_smb_path), smb.path.ifEmpty { stringResource(R.string.settings_smb_path_root) })
                ValueRow(stringResource(R.string.settings_user), if (smb.guest) stringResource(R.string.settings_smb_guest) else smb.userName)
            } else if (deviceFolder != null) {
                // 端末のフォルダ（#199）は端末の中のパス（ツリーの URI は利用者に読めない形なので出さない）
                ValueRow(stringResource(R.string.settings_device_folder), deviceFolder.displayPath().resolve())
            } else if (jellyfinSession != null) {
                ValueRow("URL", jellyfinSession.serverUrl)
                ValueRow(stringResource(R.string.settings_user), jellyfinSession.userName)
                ValueRow(
                    stringResource(R.string.settings_library),
                    jellyfin?.library?.name ?: stringResource(R.string.settings_library_none),
                    modifier = Modifier.clickable(onClick = onChangeLibrary),
                    supporting = stringResource(R.string.settings_library_tap),
                )
            }
            ValueRow(stringResource(R.string.settings_last_sync), (s as? SessionState.Ready)?.lastFetchedAt?.toDateTimeText() ?: "-")
            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_download))
            // 手元のファイルの合計（#42）。保持ルールや固定を調整する動機は容量なので、見えるようにする。押すと内訳の画面（#335）
            ValueRow(
                stringResource(R.string.settings_storage),
                localStorage?.toText()?.resolve() ?: "-",
                modifier = Modifier.clickable(onClick = onOpenStorage),
                supporting = stringResource(R.string.settings_storage_tap),
            )
            // 取得元に見つからない番組（消失・取得元 ID 無し）をまとめて消す（#317）。無ければ行ごと出さない。同期の間は押せない
            if (unmatched.programCount > 0) {
                ListItem(
                    modifier = Modifier.clickable(enabled = !isSyncing, onClick = onRemoveUnmatched),
                    headlineContent = {
                        Text(
                            pluralStringResource(R.plurals.settings_remove_unmatched, unmatched.programCount, unmatched.programCount),
                            color = if (isSyncing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    },
                    supportingContent = if (isSyncing) {
                        { Text(stringResource(R.string.settings_remove_unmatched_disabled)) }
                    } else {
                        null
                    },
                )
            }
            ListItem(
                modifier = Modifier.clickable { onSetWifiOnly(!wifiOnly) },
                headlineContent = { Text(stringResource(R.string.settings_wifi_only)) },
                supportingContent = { Text(stringResource(R.string.settings_wifi_only_description)) },
                trailingContent = { Switch(checked = wifiOnly, onCheckedChange = onSetWifiOnly) },
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
                                onClick = { onSetThemeMode(choice) },
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
                modifier = Modifier.clickable(enabled = isConnected) { confirmSignOut = true },
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
            confirmButton = { TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text(stringResource(R.string.settings_sign_out)) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    removePreview?.let { preview ->
        val programs = pluralStringResource(R.plurals.settings_remove_unmatched_programs, preview.programCount, preview.programCount)
        val episodes = if (preview.pinnedEpisodeCount > 0) {
            pluralStringResource(R.plurals.settings_remove_unmatched_episodes_pinned, preview.localEpisodeCount, preview.localEpisodeCount, preview.pinnedEpisodeCount)
        } else {
            pluralStringResource(R.plurals.settings_remove_unmatched_episodes, preview.localEpisodeCount, preview.localEpisodeCount)
        }
        AlertDialog(
            onDismissRequest = onCancelRemoveUnmatched,
            title = { Text(stringResource(R.string.settings_remove_unmatched_title)) },
            text = { Text(stringResource(R.string.settings_remove_unmatched_text, programs, episodes)) },
            confirmButton = {
                TextButton(onClick = onConfirmRemoveUnmatched) {
                    Text(stringResource(R.string.settings_remove_unmatched_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onCancelRemoveUnmatched) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (confirmReset) {
        ChangeSourceConfirmDialog(
            onConfirm = { confirmReset = false; onResetAndConnectElsewhere() },
            onDismiss = { confirmReset = false },
        )
    }
}

private fun ThemeMode.label(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.DARK -> R.string.settings_theme_dark
    ThemeMode.LIGHT -> R.string.settings_theme_light
}

private const val SOURCE_URL = "https://github.com/t-seki/kikidame"

private val previewJellyfinSession = Session(serverUrl = "https://jellyfin.example.com", userName = "alice", userId = "u1", accessToken = "t")

@Composable
private fun SettingsContentPreviewHost(session: SessionState) {
    KikidameTheme {
        SettingsContent(
            session = session,
            wifiOnly = true,
            localStorage = LocalStorageUsage(totalBytes = 1_234_567_890L, episodeCount = 12),
            themeMode = ThemeMode.SYSTEM,
            unmatched = UnmatchedPrograms(programCount = 2, localEpisodeCount = 5, pinnedEpisodeCount = 1),
            isSyncing = false,
            removePreview = null,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onChangeLibrary = {},
            onOpenLicenses = {},
            onOpenStorage = {},
            onRemoveUnmatched = {},
            onConfirmRemoveUnmatched = {},
            onCancelRemoveUnmatched = {},
            onSetWifiOnly = {},
            onSetThemeMode = {},
            onSignOut = {},
            onResetAndConnectElsewhere = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun SettingsContentPreview() {
    SettingsContentPreviewHost(
        SessionState.Ready(
            ConnectedSource.Jellyfin(previewJellyfinSession, SelectedLibrary(SourceItemId("lib1"), "ラジオ")),
            lastFetchedAt = Instant.fromEpochSeconds(1_760_000_000),
        ),
    )
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun SettingsContentNeedsLibraryPreview() {
    SettingsContentPreviewHost(SessionState.NeedsLibrary(previewJellyfinSession))
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun SettingsContentSmbPreview() {
    SettingsContentPreviewHost(
        SessionState.Ready(
            ConnectedSource.Smb(SmbConnection(host = "nas.local", share = "radio", path = "録音/2026", userName = "alice", password = "")),
            lastFetchedAt = Instant.fromEpochSeconds(1_760_000_000),
        ),
    )
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun SettingsContentDevicePreview() {
    SettingsContentPreviewHost(
        SessionState.Ready(
            ConnectedSource.Device(DeviceFolder(treeUri = "content://com.android.externalstorage.documents/tree/primary%3ARadio", name = "Radio")),
            lastFetchedAt = null,
        ),
    )
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun SettingsContentSignedOutPreview() {
    SettingsContentPreviewHost(SessionState.SignedOut(lastServerUrl = null, lastUserName = null))
}
