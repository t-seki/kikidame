package dev.tseki.kikidame.ui.library

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.ui.resolve

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
                title = { Text(stringResource(R.string.library_pick_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
                if (state.libraries != null && state.musicCount == 0) {
                    Text(
                        stringResource(R.string.library_pick_no_music),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Button(
                    onClick = { viewModel.confirm(onDone) },
                    enabled = state.canConfirm,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.library_pick_confirm)) }
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
                Text(state.error!!.resolve(), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::load) { Text(stringResource(R.string.library_pick_reload)) }
            }
            else -> LazyColumn(Modifier.padding(padding)) {
                // 選べない理由は行ごとに繰り返さず、一覧の上に 1 回だけ（#71）。音楽以外が無ければ出さない
                if (state.libraries.orEmpty().any { !it.isMusic }) {
                    item(key = "note") {
                        Text(
                            stringResource(R.string.library_pick_music_only),
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
            val type = library.collectionType?.let { collectionTypeLabel(it) } ?: if (library.isMusic) stringResource(R.string.library_pick_type_music) else null
            if (type != null) Text(type, color = if (library.isMusic) MaterialTheme.colorScheme.onSurfaceVariant else disabledColor)
        },
    )
}

@Composable
private fun collectionTypeLabel(type: String): String = when (type) {
    "music" -> stringResource(R.string.library_pick_type_music)
    "movies" -> stringResource(R.string.library_pick_type_movies)
    "tvshows" -> stringResource(R.string.library_pick_type_tvshows)
    "photos", "homevideos" -> stringResource(R.string.library_pick_type_photos)
    "books" -> stringResource(R.string.library_pick_type_books)
    "musicvideos" -> stringResource(R.string.library_pick_type_musicvideos)
    "boxsets" -> stringResource(R.string.library_pick_type_boxsets)
    "playlists" -> stringResource(R.string.library_pick_type_playlists)
    "livetv" -> stringResource(R.string.library_pick_type_livetv)
    else -> type
}
