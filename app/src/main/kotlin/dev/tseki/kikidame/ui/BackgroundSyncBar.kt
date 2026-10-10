package dev.tseki.kikidame.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.ScanProgress

/**
 * 裏の同期（定期・起動時）が走っている間、トップバーの下に出す細いバーと小さな文字（#138）。
 * 同期が終わって一覧が書き換わる理由を控えめに示す。「引っ張って更新」のクルクル（利用者の操作を処理中）とは見た目を分け、
 * 手動の操作が合流したらこちらは消えてクルクルに切り替わる（[dev.tseki.kikidame.sync.LibraryRefresher.isSyncingInBackground]）。
 * 番組一覧と各回一覧で使う。
 *
 * [progress]（全走査でタグを読んでいる数。#209 の決定 6）があれば、裏の同期でも手動の同期でも出し、目盛りのあるバーと
 * 「タグを読み込み中 1,234 / 6,090」を出す。番組一覧だけが渡す。
 */
@Composable
fun BackgroundSyncBar(visible: Boolean, progress: ScanProgress? = null) {
    if (!visible && progress == null) return
    Column(Modifier.fillMaxWidth()) {
        if (progress != null) {
            LinearProgressIndicator(
                progress = { if (progress.total > 0) progress.read.toFloat() / progress.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text(
            if (progress != null) {
                stringResource(R.string.sync_reading_tags, progress.read, progress.total)
            } else {
                stringResource(R.string.sync_in_background)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}
