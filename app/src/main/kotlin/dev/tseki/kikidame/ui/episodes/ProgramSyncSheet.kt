package dev.tseki.kikidame.ui.episodes

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.LocalStorageUsage
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.ui.resolve
import dev.tseki.kikidame.text.toPublishedDateText
import dev.tseki.kikidame.ui.toText

/** 同期のシートの状態。「番組を手元から消す」はシートを閉じて確認ダイアログを開く遷移なので、1 つの値で持つ。 */
internal enum class SyncSheetState { Hidden, Open, ConfirmRemove }

/**
 * 番組の同期のシート（[ProgramSyncSheet]）と、番組を消す確認・同期をやめる確認の 2 つのダイアログ。
 * 状態は呼び出し側が持ち（トップバーのボタンで開くため）、番組が読めるまでは何も出さない。ViewModel は呼ばない
 * （シートを開いた・閉じたを伝えるのは呼び出し側）。
 * 同期をやめる確認は、呼び出し側から引数で渡される [pendingDisable]（null なら出さない）で開き、[SyncSheetState] とは関係ない。
 */
@Composable
internal fun ProgramSyncDialogs(
    program: Program?,
    localStorage: LocalStorageUsage?,
    pendingDisable: Int?,
    state: SyncSheetState,
    onStateChange: (SyncSheetState) -> Unit,
    actions: EpisodeListActions,
) {
    if (state == SyncSheetState.Open && program != null) {
        ProgramSyncSheet(
            program = program,
            localStorage = localStorage,
            onDismiss = { onStateChange(SyncSheetState.Hidden) },
            onSetEnabled = actions.setSyncEnabled,
            onKeepLatest = actions.setKeepLatest,
            onDeleteAfterPlayed = actions.setDeleteAfterPlayed,
            onRemoveProgram = { onStateChange(SyncSheetState.ConfirmRemove) },
        )
    }
    if (state == SyncSheetState.ConfirmRemove && program != null) {
        AlertDialog(
            onDismissRequest = { onStateChange(SyncSheetState.Hidden) },
            title = { Text(stringResource(R.string.episode_list_remove_program_title)) },
            text = { Text(stringResource(R.string.episode_list_remove_program_text, program.name)) },
            confirmButton = {
                TextButton(onClick = { onStateChange(SyncSheetState.Hidden); actions.removeProgram() }) {
                    Text(stringResource(R.string.episode_list_remove_program_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { onStateChange(SyncSheetState.Hidden) }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    pendingDisable?.let { count ->
        AlertDialog(
            onDismissRequest = actions.cancelDisableSync,
            title = { Text(stringResource(R.string.episode_list_disable_sync_title)) },
            text = { Text(pluralStringResource(R.plurals.episode_list_disable_sync_text, count, count)) },
            confirmButton = {
                TextButton(onClick = actions.confirmDisableSync) { Text(stringResource(R.string.episode_list_disable_sync_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = actions.cancelDisableSync) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/**
 * 同期対象と保持ルールの編集。変えている間は保存するだけで、何も消えない。シートを閉じたとき、開いたときから設定が変わっていれば
 * その番組の同期が走って適用される（ダウンロードも削除も）。開いた・閉じたを ViewModel に伝えるのはこのシートでも [ProgramSyncDialogs] でもなく、
 * 呼び出し側の `EpisodeListContent` が状態が [SyncSheetState.Open] の間だけ置く `DisposableEffect`。
 * 取得元 ID の無い番組は同期できないのでスイッチを無効にする。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProgramSyncSheet(
    program: Program,
    localStorage: LocalStorageUsage?,
    onDismiss: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onKeepLatest: (Int?) -> Unit,
    onDeleteAfterPlayed: (Boolean) -> Unit,
    onRemoveProgram: () -> Unit,
) {
    // 消失した番組（取得元の一覧に無く、突合でも結び直せなかった）と取得元 ID の無い番組は同期できない
    val canSync = program.sourceItemId != null && !program.isGone
    val enabled = program.syncEnabled
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(program.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        // この番組の手元のファイル。保持ルールを触る前に目に入る位置に
        localStorage?.let {
            Text(
                if (it.episodeCount > 0) stringResource(R.string.episode_list_storage, it.toText().resolve()) else stringResource(R.string.episode_list_storage_empty),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        ListItem(
            modifier = Modifier.combinedClickable(enabled = canSync) { onSetEnabled(!enabled) },
            headlineContent = { Text(stringResource(R.string.episode_list_sync_this_program)) },
            supportingContent = {
                Text(
                    when {
                        program.isGone -> stringResource(R.string.episode_list_sync_gone, program.goneSince?.toPublishedDateText().orEmpty())
                        !canSync -> stringResource(R.string.episode_list_sync_unavailable)
                        else -> stringResource(R.string.episode_list_sync_description)
                    },
                )
            },
            trailingContent = { Switch(checked = enabled && canSync, onCheckedChange = onSetEnabled, enabled = canSync) },
        )
        ListItem(
            modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
            headlineContent = { Text(stringResource(R.string.episode_list_keep_latest)) },
            supportingContent = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    for (choice in EpisodeListViewModel.KEEP_LATEST_CHOICES) {
                        FilterChip(
                            selected = program.retentionRule.keepLatest == choice,
                            onClick = { onKeepLatest(choice) },
                            enabled = enabled,
                            label = { Text(choice?.let { pluralStringResource(R.plurals.episode_list_keep_latest_choice, it, it) } ?: stringResource(R.string.episode_list_keep_unlimited)) },
                        )
                    }
                }
            },
        )
        ListItem(
            modifier = Modifier
                .alpha(if (enabled) 1f else 0.5f)
                .combinedClickable(enabled = enabled) { onDeleteAfterPlayed(!program.retentionRule.deleteAfterPlayed) },
            headlineContent = { Text(stringResource(R.string.episode_list_delete_after_played)) },
            supportingContent = { Text(stringResource(R.string.episode_list_delete_after_played_description)) },
            trailingContent = {
                Switch(checked = program.retentionRule.deleteAfterPlayed, onCheckedChange = onDeleteAfterPlayed, enabled = enabled)
            },
        )
        if (!canSync) {
            // 取得元に在る番組を消しても次の同期で戻ってくる（再生位置だけ失う）ので、消せるのは取得元に無い番組だけ
            SheetAction(Icons.Filled.Delete, stringResource(R.string.episode_list_remove_program)) { onRemoveProgram() }
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}
