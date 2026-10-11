package dev.tseki.kikidame.ui.programs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.tseki.kikidame.R
import dev.tseki.kikidame.ui.programs.ProgramFilter.PublisherKey
import dev.tseki.kikidame.ui.resolve

/**
 * 空の状態の包み。PullToRefreshBox の中身はスクロール可能である必要があるので、中央寄せの Box を LazyColumn の 1 項目で包む。
 * 3 つの空の状態（[NoMatch]・[EmptyPrograms]・[PageMessage]）は中身だけが違う。
 */
@Composable
private fun CenteredPageMessage(content: @Composable () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}

/** 絞り込みが効いていて該当が無いとき。番組自体が無いのとは別物なので、更新の案内は出さない。 */
@Composable
internal fun NoMatch(query: String, publisher: PublisherKey?) {
    CenteredPageMessage {
        // 「番組一覧」は配信元だけで 0 件にはならない（配信元の候補は手元の番組から集めるので）。
        // 「よく聴く」は配信元だけでも 0 件になる（その配信元によく聴く番組が無い）
        val normalized = ProgramFilter.normalize(query)
        Text(
            when {
                normalized.isEmpty() && publisher != null ->
                    stringResource(R.string.program_list_no_starred_in_publisher, publisher.label.resolve())
                publisher != null -> stringResource(R.string.program_list_no_match_in_publisher, normalized, publisher.label.resolve())
                else -> stringResource(R.string.program_list_no_match, normalized)
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun EmptyPrograms(canRefresh: Boolean) {
    CenteredPageMessage {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.program_list_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(
                if (canRefresh) stringResource(R.string.program_list_empty_pull) else stringResource(R.string.program_list_empty_connect),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 空のタブの案内（#151。「続きから」「よく聴く」）。引っ張って更新できるよう LazyColumn で包む。 */
@Composable
internal fun PageMessage(text: String) {
    CenteredPageMessage {
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}
