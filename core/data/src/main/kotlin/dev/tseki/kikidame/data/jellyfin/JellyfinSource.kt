package dev.tseki.kikidame.data.jellyfin

import dev.tseki.kikidame.data.session.SessionStore
import dev.tseki.kikidame.data.source.DownloadStream
import dev.tseki.kikidame.data.source.ScanListener
import dev.tseki.kikidame.data.source.SourceGateway
import dev.tseki.kikidame.domain.ConnectedSource
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.SourceException
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Jellyfin のライブラリ（音楽かブック。#354）を取得元（[SourceGateway]）として見せる（ADR 0010）。
 * 認証の情報（[ServerCredentials]）とライブラリは、呼ばれるたびに [SessionStore] から読む。
 * 番組についての呼び出しは、ライブラリの種類で番組の組み方が変わるので、選んだライブラリも渡す（ADR 0013 の決定 2）。
 */
@Singleton
class JellyfinSource @Inject constructor(
    private val gateway: JellyfinGateway,
    private val store: SessionStore,
) : SourceGateway {

    /** 選んだライブラリの全体。ライブラリを選んでいなければ [SourceException.Unauthorized]。一覧が一度に返るので [listener] は使わない。 */
    override suspend fun fetchAll(listener: ScanListener): SourceSnapshot {
        val ready = (store.current() as? SessionState.Ready)?.source as? ConnectedSource.Jellyfin ?: throw SourceException.Unauthorized()
        return gateway.fetchLibrary(ready.session.credentials(), ready.library)
    }

    override suspend fun fetchProgramEpisodes(programId: SourceItemId): List<SourceEpisode> {
        val (credentials, library) = withLibrary()
        return gateway.fetchProgramEpisodes(credentials, library, programId)
    }

    override suspend fun fetchProgram(programId: SourceItemId): SourceProgram? {
        val (credentials, library) = withLibrary()
        return gateway.fetchProgram(credentials, library, programId)
    }

    override suspend fun fetchProgramImage(programId: SourceItemId): ByteArray? {
        val (credentials, library) = withLibrary()
        return gateway.fetchProgramImage(credentials, library, programId)
    }

    override suspend fun openDownload(episodeId: SourceItemId, rangeStart: Long): DownloadStream =
        gateway.openDownload(credentials(), episodeId, rangeStart)

    /** ダウンロードはライブラリを選ぶ前（[SessionState.NeedsLibrary]）でも行う（`DownloadWorker` の挙動）。 */
    private suspend fun credentials(): ServerCredentials = when (val s = store.current()) {
        is SessionState.Ready -> (s.source as? ConnectedSource.Jellyfin)?.session?.credentials() ?: throw SourceException.Unauthorized()
        is SessionState.NeedsLibrary -> s.session.credentials()
        is SessionState.SignedOut -> throw SourceException.Unauthorized()
    }

    /**
     * 番組についての呼び出しに渡す認証の情報とライブラリ。ログイン済みでライブラリを選ぶ前（[SessionState.NeedsLibrary]）は、
     * 番組の組み方が決まらないので [SourceException.Failed]（ログアウトはさせない）。ログアウト中は [SourceException.Unauthorized]。
     */
    private suspend fun withLibrary(): Pair<ServerCredentials, SelectedLibrary> = when (val s = store.current()) {
        is SessionState.Ready -> (s.source as? ConnectedSource.Jellyfin)?.let { it.session.credentials() to it.library }
            ?: throw SourceException.Unauthorized()
        is SessionState.NeedsLibrary -> throw SourceException.Failed("no library selected")
        is SessionState.SignedOut -> throw SourceException.Unauthorized()
    }
}
