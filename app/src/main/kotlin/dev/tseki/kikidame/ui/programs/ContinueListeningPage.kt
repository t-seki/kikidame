package dev.tseki.kikidame.ui.programs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.Episode
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LocalFile
import dev.tseki.kikidame.domain.PlaybackState
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.playback.NowPlayingState
import dev.tseki.kikidame.text.UiText
import dev.tseki.kikidame.ui.episodes.EpisodeActionsSheet
import dev.tseki.kikidame.ui.episodes.EpisodeRow
import dev.tseki.kikidame.ui.episodes.PlayedToggleButton
import dev.tseki.kikidame.ui.theme.KikidameTheme
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * 「続きから」タブの中身。行のタップは各回一覧と同じく再生画面を開いて続きから再生する（キューはその番組の手元の回）。
 * 空なら案内を出す。
 *
 * 値とラムダだけを取り、ViewModel を受け取らない。[items] と [nowPlaying] は [ContinueListeningViewModel] から
 * 外側の [ProgramListScreen] が集めて渡す。[items] が null なら読み込み前。
 */
@Composable
internal fun ContinueListeningPage(
    items: List<ContinueListeningViewModel.Item>?,
    nowPlaying: NowPlayingState?,
    listState: LazyListState,
    onEpisodeClick: (EpisodeId) -> Unit,
    onEpisodeDetails: (ProgramId, EpisodeId) -> Unit,
    onSetPlayed: (EpisodeId, Boolean) -> Unit,
    onPin: (EpisodeId) -> Unit,
    onUnpin: (EpisodeId) -> Unit,
    onDeleteLocal: (EpisodeId) -> Unit,
) {
    var sheetFor by remember { mutableStateOf<EpisodeId?>(null) }
    val list = items
    when {
        list == null -> Box(Modifier.fillMaxSize())
        list.isEmpty() -> PageMessage(stringResource(R.string.program_list_continue_empty))
        else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(list, key = { it.episode.episode.id.value }) { item ->
                val id = item.episode.episode.id
                EpisodeRow(
                    item = item.episode,
                    nowPlaying = nowPlaying?.takeIf { it.episodeId == id },
                    supportingText = item.toSupportingText(),
                    onClick = { onEpisodeClick(id) },
                    onLongClick = { sheetFor = id },
                    trailing = {
                        // 回は必ず手元にあるので、ダウンロードの状態ではなく常に再生済みの切り替え
                        PlayedToggleButton(
                            played = item.episode.playback?.played == true,
                            onToggle = { onSetPlayed(id, item.episode.playback?.played != true) },
                        )
                    },
                )
                HorizontalDivider()
            }
        }
    }
    // 再生済みにした・消した回は一覧から消えるので、シートの対象も一覧から引き直す（消えたらシートも閉じる）
    val target = sheetFor?.let { id -> list?.firstOrNull { it.episode.episode.id == id } }
    if (target != null) {
        val episode = target.episode.episode
        EpisodeActionsSheet(
            item = target.episode,
            syncEnabled = target.program?.syncEnabled == true,
            onDownload = null, // 「続きから」の回は必ず手元にある
            onDismiss = { sheetFor = null },
            onPin = { onPin(episode.id) },
            onUnpin = { onUnpin(episode.id) },
            onDelete = { onDeleteLocal(episode.id) },
            onDetails = { sheetFor = null; onEpisodeDetails(episode.programId, episode.id) },
        )
    }
}

/**
 * 行の補足: `番組名 · 残り N 分`。残りは次に再生したとき始まる位置（[dev.tseki.kikidame.domain.EpisodeWithState.resumePosition]。
 * バーと同じ）から末尾までで、分に切り上げる（「残り 0 分」は出さない）。尺が分からない回（runtime 0）は残りを省いて番組名だけ。
 */
internal fun ContinueListeningViewModel.Item.toSupportingText(): UiText {
    val runtime = episode.episode.runtime
    val remaining = if (runtime > Duration.ZERO) {
        val left = (runtime - (episode.resumePosition ?: Duration.ZERO)).coerceAtLeast(Duration.ZERO)
        val minutes = ceil(left.toDouble(DurationUnit.MINUTES)).toInt().coerceAtLeast(1)
        UiText.Plural(R.plurals.program_list_continue_remaining, minutes, minutes)
    } else {
        null
    }
    return UiText.Joined(listOfNotNull(program?.name?.let(UiText::Plain), remaining), UiText.Plain(" · "))
}

private val previewItems: List<ContinueListeningViewModel.Item> = run {
    val now = Instant.parse("2026-10-11T00:00:00Z")
    val news = Program(ProgramId(1), null, "朝のニュース", "文化放送", syncEnabled = true)
    val music = Program(ProgramId(2), null, "深夜の音楽室", "TBS ラジオ")
    fun item(id: Long, program: Program, title: String, published: String, position: Duration, runtime: Duration) =
        ContinueListeningViewModel.Item(
            episode = EpisodeWithState(
                episode = Episode(
                    id = EpisodeId(id),
                    sourceItemId = null,
                    programId = program.id,
                    title = title,
                    publishedAt = Instant.parse("${published}T15:00:00Z"),
                    addedAt = null,
                    runtime = runtime,
                    sizeBytes = 50_000_000,
                    container = "mp3",
                ),
                localFile = LocalFile(EpisodeId(id), DownloadState.DONE, path = "/preview/$id.mp3", pinned = false),
                playback = PlaybackState(EpisodeId(id), position, played = false, updatedAt = now),
            ),
            program = program,
        )
    listOf(
        item(11, news, "10月10日の放送", "2026-10-09", 12.minutes, 30.minutes),
        item(21, music, "第42回 秋の夜長に", "2026-10-07", 40.minutes, 55.minutes),
        item(12, news, "10月9日の放送", "2026-10-08", 3.minutes, 30.minutes),
    )
}

@Preview
@Composable
private fun ContinueListeningPagePreview() {
    KikidameTheme {
        ContinueListeningPage(
            items = previewItems,
            nowPlaying = NowPlayingState(EpisodeId(11), "10月10日の放送", "朝のニュース", isPlaying = true),
            listState = rememberLazyListState(),
            onEpisodeClick = {},
            onEpisodeDetails = { _, _ -> },
            onSetPlayed = { _, _ -> },
            onPin = {},
            onUnpin = {},
            onDeleteLocal = {},
        )
    }
}

@Preview
@Composable
private fun ContinueListeningPageEmptyPreview() {
    KikidameTheme {
        ContinueListeningPage(
            items = emptyList(),
            nowPlaying = null,
            listState = rememberLazyListState(),
            onEpisodeClick = {},
            onEpisodeDetails = { _, _ -> },
            onSetPlayed = { _, _ -> },
            onPin = {},
            onUnpin = {},
            onDeleteLocal = {},
        )
    }
}
