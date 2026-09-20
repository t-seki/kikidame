package dev.tseki.kikidame.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import dev.tseki.kikidame.R

/**
 * 依存ライブラリのライセンス一覧。中身は `res/raw/aboutlibraries.json`（`./gradlew :app:exportLibraryDefinitions` で生成してコミット）。
 * jellyfin-sdk-kotlin（LGPL-3.0）を同梱した APK を配るので、使っていることとライセンス本文をアプリ内で示す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("オープンソースライセンス") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                },
            )
        },
    ) { padding ->
        LibrariesContainer(libraries, Modifier.fillMaxSize().padding(padding))
    }
}
