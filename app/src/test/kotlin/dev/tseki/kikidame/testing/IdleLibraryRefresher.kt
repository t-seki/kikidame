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
 * 画面の ViewModel に渡すための [LibraryRefresher]。取得元には問い合わせず（更新・同期は null を返す）、ダウンロードも起こさない。
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
    override suspend fun refreshProgram(programId: ProgramId): RefreshResult? = null
    override suspend fun syncProgram(programId: ProgramId, excluded: Set<EpisodeId>): RefreshResult? = null
}

private object UnmeteredNetwork : NetworkStatus {
    override fun isMetered() = false
}
