package dev.tseki.kikidame.data.jellyfin

import dev.tseki.kikidame.data.session.SessionStore
import dev.tseki.kikidame.data.source.DownloadStream
import dev.tseki.kikidame.data.source.SourceGateway
import dev.tseki.kikidame.domain.ConnectedSource
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Jellyfin の音楽ライブラリを取得元（[SourceGateway]）として見せる（ADR 0010）。
 * 認証の情報（[ServerCredentials]）とライブラリは、呼ばれるたびに [SessionStore] から読む。
 */
@Singleton
class JellyfinSource @Inject constructor(
    private val gateway: JellyfinGateway,
    private val store: SessionStore,
) : SourceGateway {

    /** 選んだライブラリの全体。ライブラリを選んでいなければ [ServerException.Unauthorized]。 */
    override suspend fun fetchAll(): SourceSnapshot {
        val ready = (store.current() as? SessionState.Ready)?.source as? ConnectedSource.Jellyfin ?: throw ServerException.Unauthorized()
        return gateway.fetchLibrary(ready.session.credentials(), ready.library.id)
    }

    override suspend fun fetchProgramEpisodes(programId: SourceItemId): List<SourceEpisode> =
        gateway.fetchProgramEpisodes(credentials(), programId)

    override suspend fun fetchProgram(programId: SourceItemId): SourceProgram? =
        gateway.fetchProgram(credentials(), programId)

    override suspend fun openDownload(episodeId: SourceItemId, rangeStart: Long): DownloadStream =
        gateway.openDownload(credentials(), episodeId, rangeStart)

    /** ダウンロードはライブラリを選ぶ前（[SessionState.NeedsLibrary]）でも行う（`DownloadWorker` の挙動）。 */
    private suspend fun credentials(): ServerCredentials = when (val s = store.current()) {
        is SessionState.Ready -> (s.source as? ConnectedSource.Jellyfin)?.session?.credentials() ?: throw ServerException.Unauthorized()
        is SessionState.NeedsLibrary -> s.session.credentials()
        is SessionState.SignedOut -> throw ServerException.Unauthorized()
    }
}
