package dev.tseki.jellyfinradio.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.jellyfinradio.domain.LibraryView

/**
 * ライブラリ選択。全ライブラリを列挙し、音楽ライブラリだけ選べる（他はグレーアウトし、理由は一覧の上に 1 回）。
 * 音楽ライブラリが 1 つでも画面は出す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryPickScreen(
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
    viewModel: LibraryPickViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ライブラリを選ぶ") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
                if (state.libraries != null && state.musicCount == 0) {
                    Text(
                        "音楽ライブラリがありません。Jellyfin 側でラジオ録音を音楽ライブラリとして追加してから再読み込みしてください",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Button(
                    onClick = { viewModel.confirm(onDone) },
                    enabled = state.canConfirm,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("決定") }
            }
        },
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::load) { Text("再読み込み") }
            }
            else -> LazyColumn(Modifier.padding(padding)) {
                // 選べない理由は行ごとに繰り返さず、一覧の上に 1 回だけ（#71）。音楽以外が無ければ出さない
                if (state.libraries.orEmpty().any { !it.isMusic }) {
                    item(key = "note") {
                        Text(
                            "音楽ライブラリのみ選べます",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(state.libraries.orEmpty(), key = { it.id.value }) { library ->
                    LibraryRow(library, selected = library.id == state.selectedId, onClick = { viewModel.select(library) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(library: LibraryView, selected: Boolean, onClick: () -> Unit) {
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    ListItem(
        modifier = Modifier.clickable(enabled = library.isMusic, onClick = onClick),
        leadingContent = { RadioButton(selected = selected, onClick = onClick, enabled = library.isMusic) },
        headlineContent = {
            Text(library.name, color = if (library.isMusic) MaterialTheme.colorScheme.onSurface else disabledColor)
        },
        supportingContent = {
            // 補足は種類だけ（種類が取れない音楽ライブラリは「音楽」、それ以外で取れなければ何も出さない）
            val type = library.collectionType?.let { collectionTypeLabel(it) } ?: if (library.isMusic) "音楽" else null
            if (type != null) Text(type, color = if (library.isMusic) MaterialTheme.colorScheme.onSurfaceVariant else disabledColor)
        },
    )
}

private fun collectionTypeLabel(type: String): String = when (type) {
    "music" -> "音楽"
    "movies" -> "映画"
    "tvshows" -> "番組（TV）"
    "photos", "homevideos" -> "写真・ホームビデオ"
    "books" -> "本"
    "musicvideos" -> "ミュージックビデオ"
    "boxsets" -> "コレクション"
    "playlists" -> "プレイリスト"
    "livetv" -> "ライブ TV"
    else -> type
}
