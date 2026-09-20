package dev.tseki.kikidame.ui.episodes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.ui.UiText
import dev.tseki.kikidame.ui.resolve
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.Episode
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.EpisodeWithState
import dev.tseki.kikidame.domain.LocalFile
import dev.tseki.kikidame.domain.PlaybackState
import dev.tseki.kikidame.domain.Program
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.ServerItemId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 各回の詳細（#43）の値の組み立て。ラベルと定型の値は [UiText] なので、日本語（`values-ja/`）で解決して比較する（Robolectric）。 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "ja")
class EpisodeDetailsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val published = Instant.parse("2026-09-17T15:00:00Z") // JST 2026-09-18
    private val at = Instant.parse("2026-09-18T03:04:00Z") // JST 12:04
    private val episode = Episode(
        id = EpisodeId(42),
        serverItemId = ServerItemId("abc123"),
        programId = ProgramId(1),
        title = "2026-09-18",
        publishedAt = published,
        addedAt = at,
        runtime = 60.minutes,
        sizeBytes = 21_900_000,
        container = "m4a",
    )
    private val program = Program(id = ProgramId(1), serverItemId = null, name = "ハライチのターン！", publisherName = "TBSラジオ")

    @Test
    fun userSectionsCollapseMissingFileAndPlayback() {
        val sections = EpisodeDetails.sections(EpisodeWithState(episode, null, null), program)
        assertEquals(listOf("各回", "手元", "再生"), sections.map { it.title.resolve(context) })
        assertEquals(
            listOf(Row("公開日", "2026-09-18"), Row("出演者", "なし"), Row("配信元", "TBSラジオ"), Row("尺", "1:00:00"), Row("サイズ", "21.9 MB")),
            sections[0].rows.text(),
        )
        assertEquals(listOf(Row("状態", "手元に無い")), sections[1].rows.text())
        assertEquals(listOf(Row("再生位置", "記録なし")), sections[2].rows.text())
    }

    @Test
    fun userSectionsShowFileAndPlayback() {
        val item = EpisodeWithState(
            episode,
            LocalFile(episode.id, DownloadState.DONE, "/x/a.m4a", pinned = true, downloadedAt = at),
            PlaybackState(episode.id, 12.minutes, played = false, updatedAt = at),
        )
        val sections = EpisodeDetails.sections(item, program)
        assertEquals(
            listOf(Row("状態", "ダウンロード済み"), Row("固定", "固定（保持ルールの対象外）"), Row("ダウンロード日時", "2026-09-18 12:04")),
            sections[1].rows.text(),
        )
        assertEquals(listOf(Row("再生位置", "12:00 / 1:00:00"), Row("再生済み", "未再生")), sections[2].rows.text())
    }

    /** 出演者（#70）は「、」で並べ、配信元は番組から。配信元の無い番組は「不明」。 */
    @Test
    fun performersAndPublisherRows() {
        val item = EpisodeWithState(episode.copy(performers = listOf("岩井勇気", "澤部佑")), null, null)
        assertEquals(Row("出演者", "岩井勇気、澤部佑"), EpisodeDetails.sections(item, program)[0].rows[1].text())
        assertEquals(Row("配信元", "不明"), EpisodeDetails.sections(item, program.copy(publisherName = null))[0].rows[2].text())
    }

    @Test
    fun technicalRowsUseRawValuesAndSkipSyncedAt() {
        val item = EpisodeWithState(
            episode,
            LocalFile(episode.id, DownloadState.FAILED, null, pinned = false, attemptCount = 3, lastAttemptAt = at),
            PlaybackState(episode.id, 12.minutes, played = false, updatedAt = at, syncedAt = at),
        )
        val rows = EpisodeDetails.technicalRows(item).text()
        assertEquals(Row("サーバ ID", "abc123"), rows[0])
        assertEquals(Row("ID（アプリ内）", "42"), rows[1])
        assertEquals(Row("公開日（記録の瞬間、UTC）", "2026-09-17T15:00:00Z"), rows[2])
        assertTrue(rows.any { it == Row("保存先", "なし") })
        assertTrue(rows.any { it == Row("失敗回数", "3 回") })
        assertTrue(rows.any { it == Row("最終試行", "2026-09-18 12:04") })
        assertFalse(rows.any { it.first.contains("同期") || it.first.contains("synced") })
    }

    /** 1 回で成功した回: 失敗 0 回でも最終試行（開始時刻）はある。以前は「試行 0 回（最終 …）」と矛盾して見えた。 */
    @Test
    fun successfulDownloadShowsZeroFailuresAndLastAttempt() {
        val rows = EpisodeDetails.technicalRows(
            EpisodeWithState(episode, LocalFile(episode.id, DownloadState.DONE, "/x/a.m4a", pinned = false, attemptCount = 0, lastAttemptAt = at, downloadedAt = at), null),
        ).text()
        assertTrue(rows.any { it == Row("失敗回数", "0 回") })
        assertTrue(rows.any { it == Row("最終試行", "2026-09-18 12:04") })
    }
    @Test
    fun localOnlyEpisodeSaysSo() {
        val rows = EpisodeDetails.technicalRows(EpisodeWithState(episode.copy(serverItemId = null, addedAt = null), null, null)).text()
        assertEquals(Row("サーバ ID", "なし（手元だけの各回）"), rows[0])
        assertEquals(Row("取り込み日時", "なし"), rows[3])
        assertFalse(rows.any { it.first == "保存先" })
    }

    /** 期待値の側: ラベルと値の組。 */
    @Suppress("TestFunctionName")
    private fun Row(label: String, value: String): Pair<String, String> = label to value

    private fun EpisodeDetails.Row.text(): Pair<String, String> = label.resolve(context) to value.resolve(context)
    private fun List<EpisodeDetails.Row>.text(): List<Pair<String, String>> = map { it.text() }
}
