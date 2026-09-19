package dev.tseki.jellyfinradio.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 値を見せる行（docs/ui.md「共通の規則」）: ラベルが上（overline）、値が下（headline）。設定アプリと同じ向きで、
 * ラベル／値の組が並ぶ画面はラベルを目で追うのでこの向きが走査しやすい（#43 の判断）。各回の詳細と設定（#58）で使う。操作の行は headline = 操作名、supporting = 説明で、これは使わない。
 */
@Composable
fun ValueRow(label: String, value: String, modifier: Modifier = Modifier, supporting: String? = null) {
    ListItem(
        modifier = modifier,
        overlineContent = { Text(label) },
        headlineContent = { Text(value) },
        supportingContent = supporting?.let { { Text(it) } },
    )
}

/** 一覧の節の見出し。番組一覧の「よく聴く」、各回の詳細、設定の「サーバ」などで同じ形。 */
@Composable
fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
    )
}
