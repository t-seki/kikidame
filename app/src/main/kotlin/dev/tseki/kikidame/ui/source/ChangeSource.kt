package dev.tseki.kikidame.ui.source

import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.tseki.kikidame.R
import dev.tseki.kikidame.data.device.DeviceDocuments
import dev.tseki.kikidame.domain.LocalDataReset
import dev.tseki.kikidame.download.DownloadScheduler
import dev.tseki.kikidame.playback.PlayerConnection
import dev.tseki.kikidame.sync.LibraryRefresher
import dev.tseki.kikidame.sync.SyncScheduler
import javax.inject.Inject

/**
 * 「取得元を変える」の処理。設定と、ログアウト後の接続画面（Jellyfin・SMB）と端末のフォルダの選択画面が共有する。
 * 再生とダウンロード・同期の Worker を止めてから、手元のデータと音声ファイルを全部消す。止めずに消すと、
 * 開いているファイルを消したり、Worker が行を作り直したりする。
 * 最後に、端末のフォルダの永続的な権限を全部手放す。保存した URI に頼らず、持っているものを全部手放す。
 */
class SourceChanger @Inject constructor(
    private val connection: PlayerConnection,
    private val scheduler: DownloadScheduler,
    private val syncScheduler: SyncScheduler,
    private val refresher: LibraryRefresher,
    private val reset: LocalDataReset,
    private val documents: DeviceDocuments,
) {
    suspend fun changeSource() {
        runCatching { connection.use { it.stop(); it.clearMediaItems() } }
            .onFailure { Log.w("SourceChanger", "stop playback before reset failed", it) }
        scheduler.cancelAll()
        syncScheduler.cancelAll()
        // Worker の取り消しは、走っている走査（ブロッキングの I/O）や初回の走査（アプリのスコープ）を止めない。終わるのを待ってから消す
        refresher.cancelAndAwait()
        reset.resetAll()
        withContext(Dispatchers.IO) {
            for (uri in runCatching { documents.persistedTreeUris() }.getOrDefault(emptyList())) {
                runCatching { documents.releasePermission(uri) }
                    .onFailure { Log.w("SourceChanger", "release the folder permission failed", it) }
            }
        }
    }
}

/**
 * 「取得元を変える」を画面から呼ぶための ViewModel。ログアウト後の接続画面（Jellyfin・SMB）と端末のフォルダの選択画面が
 * [dev.tseki.kikidame.ui.connect.SourceConnectScaffold] を通して共有する。処理は [viewModelScope] で走らせ、画面が離れても止めない。
 */
@HiltViewModel
class ChangeSourceViewModel @Inject constructor(
    private val sourceChanger: SourceChanger,
) : ViewModel() {
    /** 手元を全部消した後に [onDone]（初回の取得元の選択画面へ戻る）を呼ぶ。 */
    fun changeSource(onDone: () -> Unit) {
        viewModelScope.launch {
            sourceChanger.changeSource()
            onDone()
        }
    }
}

/** 「取得元を変える」の入口（確認のダイアログ付き）。確認の文言は設定のものを使う。 */
@Composable
fun ChangeSourceButton(onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { confirming = true }, modifier = modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_connect_elsewhere), color = MaterialTheme.colorScheme.error)
    }
    if (confirming) {
        ChangeSourceConfirmDialog(
            onConfirm = { confirming = false; onConfirm() },
            onDismiss = { confirming = false },
        )
    }
}

/**
 * 「取得元を変える」の確認ダイアログ。設定と [ChangeSourceButton] が共有する。開いているかは呼び出し側が持つ。
 * [onConfirm] は確認ボタン、[onDismiss] は取り消しとダイアログの外を押したとき。どちらもダイアログを閉じるのは呼び出し側。
 */
@Composable
fun ChangeSourceConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_reset_title)) },
        text = { Text(stringResource(R.string.settings_reset_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_reset_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
