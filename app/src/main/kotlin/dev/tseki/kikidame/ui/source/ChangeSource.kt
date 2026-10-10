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
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.LocalDataReset
import dev.tseki.kikidame.download.DownloadScheduler
import dev.tseki.kikidame.playback.PlayerConnection
import dev.tseki.kikidame.sync.LibraryRefresher
import dev.tseki.kikidame.sync.SyncScheduler
import javax.inject.Inject

/**
 * 「取得元を変える」の処理（epic #195 の決定 2）。設定と、ログアウト後の接続画面（Jellyfin・SMB）が共有する。
 * 再生とダウンロード・同期の Worker を止めてから、手元のデータと音声ファイルを全部消す。止めずに消すと、
 * 開いているファイルを消したり、Worker が行を作り直したりする（#50）。
 */
class SourceChanger @Inject constructor(
    private val connection: PlayerConnection,
    private val scheduler: DownloadScheduler,
    private val syncScheduler: SyncScheduler,
    private val refresher: LibraryRefresher,
    private val reset: LocalDataReset,
) {
    suspend fun changeSource() {
        runCatching { connection.use { it.stop(); it.clearMediaItems() } }
            .onFailure { Log.w("SourceChanger", "stop playback before reset failed", it) }
        scheduler.cancelAll()
        syncScheduler.cancelAll()
        // Worker の取り消しは、走っている走査（ブロッキングの I/O）や初回の走査（アプリのスコープ）を止めない。終わるのを待ってから消す
        refresher.cancelAndAwait()
        reset.resetAll()
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
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_text)) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onConfirm() }) {
                    Text(stringResource(R.string.settings_reset_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
