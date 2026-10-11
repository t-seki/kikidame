package dev.tseki.kikidame.ui.programs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tseki.kikidame.R
import dev.tseki.kikidame.ui.programs.ProgramFilter.Publisher
import dev.tseki.kikidame.ui.programs.ProgramFilter.PublisherKey
import dev.tseki.kikidame.ui.resolve

/**
 * 絞り込みのシート（#55）。上段が検索欄（#44）、下段が「すべて」＋ 手元の番組から集めた配信元（#45、番組数付き）。
 * 検索語はその場で背後の一覧に効き、キーボードの検索（決定）か配信元の選択で閉じる。配信元が 2 種類未満なら [publishers] は空で、下段を出さない。
 * 縦に並べるので配信元が増えても横にはみ出さず、半開きで下の配信元が隠れないよう最初から全開。ドラッグでは閉じない。
 * M3 の SearchBar は全画面のサジェスト領域を持つ部品なので、その場で一覧を絞る用途には使わない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilterSheet(
    query: String,
    onQueryChange: (String) -> Unit,
    publishers: List<Publisher>,
    selected: PublisherKey?,
    onSelectPublisher: (PublisherKey?) -> Unit,
    onDismiss: () -> Unit,
) {
    // 配信元の一覧を先頭を越えて引っ張ると、余ったドラッグがシートに渡って閉じてしまう。最初から全開でドラッグで閉じる意味は薄いので
    // シートのドラッグ操作ごと無効にする（閉じるのは外側タップ・戻る・検索キー・配信元の選択）。取っ手も引けないので出さない
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        // シートの中身は別ウィンドウに描かれるので、フォーカスとキーボードの取得もこの中で行う
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        // 開いた瞬間に検索欄へフォーカスしてキーボードを出す（開く目的の大半は検索）
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        Text(stringResource(R.string.program_list_filter), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        // 文字は本文サイズ（見出しと区別する）。× は空欄に戻すだけで閉じない。キーボードの検索（決定）で閉じて、絞った一覧を見せる
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).focusRequester(focusRequester),
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = { Text(stringResource(R.string.program_list_filter_placeholder)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.program_list_clear_query))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onDismiss() }),
        )
        if (publishers.isNotEmpty()) {
            Text(stringResource(R.string.program_list_publisher), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 4.dp))
            // キーボードの上に収める（imePadding）。シートの高さいっぱいで測ると「全部収まっている」扱いになって
            // 一覧がスクロールせず、ドラッグがシートに渡って閉じてしまう
            LazyColumn(Modifier.weight(1f, fill = false).imePadding()) {
                item(key = "all") {
                    PublisherChoice(stringResource(R.string.program_list_all), publishers.sumOf { it.programCount }, selected == null) { onSelectPublisher(null); onDismiss() }
                }
                // 「すべて」「配信元なし」と配信元名が衝突しないよう接頭辞を付ける
                items(publishers, key = { it.key.name?.let { n -> "publisher:$n" } ?: "none" }) { publisher ->
                    PublisherChoice(publisher.key.label.resolve(), publisher.programCount, publisher.key == selected) { onSelectPublisher(publisher.key); onDismiss() }
                }
                // 末尾の余白は一覧の中に置く（外に置くと、配信元が多くて一覧が高さを使い切ったとき 0 になる）
                item { Spacer(Modifier.padding(bottom = 32.dp)) }
            }
        } else {
            Spacer(Modifier.padding(bottom = 32.dp))
        }
    }
}

@Composable
private fun PublisherChoice(label: String, programCount: Int, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(label) },
        trailingContent = { Text("$programCount", style = MaterialTheme.typography.bodyMedium) },
    )
}

/**
 * 効いている絞り込みを 1 つずつチップで示す（検索語は「」で囲み、配信元は配信元名）。チップのタップ（× を含む）でその絞り込みだけ解除する。
 * × だけを別の clickable にすると当たり判定が小さくなるので、チップ全体を解除にする。変えるのは TopAppBar の絞り込みから。
 */
@Composable
internal fun FilterChipsRow(query: String, publisher: PublisherKey?, onClearQuery: () -> Unit, onClearPublisher: () -> Unit) {
    if (query.isEmpty() && publisher == null) return
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (query.isNotEmpty()) {
            InputChip(
                selected = true,
                onClick = onClearQuery,
                label = { Text(stringResource(R.string.program_list_query_chip, query), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.program_list_clear_query_filter)) },
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (publisher != null) {
            InputChip(
                selected = true,
                onClick = onClearPublisher,
                label = { Text(publisher.label.resolve(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.program_list_clear_publisher_filter)) },
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}
