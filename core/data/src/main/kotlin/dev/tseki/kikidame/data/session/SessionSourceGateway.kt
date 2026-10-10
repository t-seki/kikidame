package dev.tseki.kikidame.data.session

import dev.tseki.kikidame.data.jellyfin.JellyfinSource
import dev.tseki.kikidame.data.sharedfolder.ScannedFiles
import dev.tseki.kikidame.data.sharedfolder.SharedFolderSource
import dev.tseki.kikidame.data.sharedfolder.TagReader
import dev.tseki.kikidame.data.smb.FolderTreeFactory
import dev.tseki.kikidame.data.source.DownloadStream
import dev.tseki.kikidame.data.source.ScanListener
import dev.tseki.kikidame.data.source.SourceGateway
import dev.tseki.kikidame.domain.ConnectedSource
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 今の接続（[SessionStore]）の取得元の種類に応じて、Jellyfin（[JellyfinSource]）か共有フォルダ（[SharedFolderSource]）に振り分ける
 * [SourceGateway]（ADR 0010、#198）。同期とダウンロードは、取得元の種類を知らずにこれを通る。呼ばれるたびに接続を読み直すので、
 * 取得元を変えた直後も古い取得元には触らない。
 *
 * 共有フォルダは、1 回の呼び出しごとに [FolderTreeFactory] で木を作って閉じる（全走査は 1 本の接続で行い、
 * ダウンロードは [DownloadStream.close] で閉じる）。接続の寿命を呼び出しの外に持ち出さない。
 * 接続していなければ（Jellyfin の接続も無ければ）[JellyfinSource] が [ServerException.Unauthorized] を投げる。
 */
@Singleton
class SessionSourceGateway @Inject constructor(
    private val store: SessionStore,
    private val jellyfin: JellyfinSource,
    private val trees: FolderTreeFactory,
    private val tags: TagReader,
    private val scanned: ScannedFiles,
) : SourceGateway {
    private val io: CoroutineDispatcher = Dispatchers.IO

    override suspend fun fetchAll(): SourceSnapshot = withSource { it.fetchAll() }

    /** 途中経過も振り分け先に渡す（共有フォルダは番組ごとに流す。#209）。全走査は 1 本の木（接続）で行う。 */
    override suspend fun fetchAll(listener: ScanListener): SourceSnapshot = withSource { it.fetchAll(listener) }

    override suspend fun fetchProgramEpisodes(programId: SourceItemId): List<SourceEpisode> =
        withSource { it.fetchProgramEpisodes(programId) }

    override suspend fun fetchProgram(programId: SourceItemId): SourceProgram? =
        withSource { it.fetchProgram(programId) }

    override suspend fun openDownload(episodeId: SourceItemId, rangeStart: Long): DownloadStream {
        val smb = smbOrNull() ?: return jellyfin.openDownload(episodeId, rangeStart)
        val tree = trees.open(smb.connection)
        try {
            val inner = SharedFolderSource(tree, tags, scanned, io).openDownload(episodeId, rangeStart)
            // inner は作り直す。body は新しい DownloadStream が閉じ、続けて木（接続）を閉じる
            return DownloadStream(inner.resumedFrom, inner.totalBytes, inner.body) { tree.close() }
        } catch (e: Throwable) {
            logFailure(e)
            withContext(NonCancellable + io) { tree.close() }
            throw e
        }
    }

    private suspend fun <T> withSource(block: suspend (SourceGateway) -> T): T {
        val smb = smbOrNull() ?: return block(jellyfin)
        val tree = trees.open(smb.connection)
        try {
            return block(SharedFolderSource(tree, tags, scanned, io))
        } catch (e: Throwable) {
            logFailure(e)
            throw e
        } finally {
            withContext(NonCancellable + io) { tree.close() }
        }
    }

    /** 共有フォルダへの接続・読み取りの失敗を原因の連鎖付きで残す（画面に出る文言には原因が出ないため）。取り消しは失敗でない。 */
    private fun logFailure(e: Throwable) {
        if (e !is CancellationException) Log.w(TAG, "shared folder access failed", e)
    }

    private companion object {
        const val TAG = "SessionSourceGateway"
    }

    private suspend fun smbOrNull(): ConnectedSource.Smb? =
        (store.current() as? SessionState.Ready)?.source as? ConnectedSource.Smb
}
