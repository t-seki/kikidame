package dev.tseki.kikidame.testing

import dev.tseki.kikidame.domain.AppSettingsRepository
import dev.tseki.kikidame.domain.DownloadRepository
import dev.tseki.kikidame.domain.EpisodeId
import dev.tseki.kikidame.domain.LibraryRefreshRepository
import dev.tseki.kikidame.domain.ProgramId
import dev.tseki.kikidame.domain.RefreshResult
import dev.tseki.kikidame.domain.ScanProgress
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.download.DownloadKicker
import dev.tseki.kikidame.playback.NowPlaying
import dev.tseki.kikidame.sync.LibraryRefresher
import dev.tseki.kikidame.sync.NetworkStatus
import kotlinx.coroutines.CoroutineScope

/**
 * 画面の ViewModel に渡すための [LibraryRefresher]。取得元には問い合わせず、ダウンロードも起こさない。
 * 中のリポジトリの1 番組の同期（`syncProgram`）は null を返す。全走査（`refresh`）は使わない前提で、
 * 呼ぶと例外を投げ、[LibraryRefresher] がそれを拾って取り込み失敗の文言（`sync_error_import_failed`）を出す。
 * [LibraryRefresher.syncProgram] は、既定では null を返して終わるが、`orFullSync = true`（各回一覧の
 * 「引っ張って更新」）ではリポジトリが null を返すと全走査に落ちるので、同じく取り込み失敗の文言になる。
 * 更新・同期そのものは `LibraryRefresherTest` で確かめる。
 */
fun idleLibraryRefresher(
    scope: CoroutineScope,
    session: SessionRepository = FakeSessionRepository(),
    downloads: DownloadRepository = FakeDownloadRepository(),
    settings: AppSettingsRepository = FakeAppSettingsRepository(),
    nowPlaying: NowPlaying = NowPlaying(),
): LibraryRefresher = LibraryRefresher(IdleRefreshRepository, session, downloads, settings, UnmeteredNetwork, nowPlaying, DownloadKicker {}, scope)

private object IdleRefreshRepository : LibraryRefreshRepository {
    override suspend fun refresh(
        excluded: () -> Set<EpisodeId>,
        onProgress: (ScanProgress) -> Unit,
        onEnqueued: (Int) -> Unit,
    ): RefreshResult = error("not used")
    override suspend fun syncProgram(programId: ProgramId, excluded: Set<EpisodeId>): RefreshResult? = null
}

private object UnmeteredNetwork : NetworkStatus {
    override fun isMetered() = false
}
