package dev.tseki.kikidame.data.session

import dev.tseki.kikidame.data.jellyfin.JellyfinSource
import dev.tseki.kikidame.data.sharedfolder.ScannedFiles
import dev.tseki.kikidame.data.sharedfolder.SharedFolderSource
import dev.tseki.kikidame.data.sharedfolder.TagReader
import dev.tseki.kikidame.data.sharedfolder.CloseableFolderTree
import dev.tseki.kikidame.data.sharedfolder.FolderTreeFactory
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
 * 今の接続（[SessionStore]）の取得元の種類に応じて、Jellyfin（[JellyfinSource]）か共有フォルダ（[SharedFolderSource]。SMB と端末のフォルダ）に振り分ける
 * [SourceGateway]（ADR 0007）。同期とダウンロードは、取得元の種類を知らずにこれを通る。呼ばれるたびに接続を読み直すので、
 * 取得元を変えた直後も古い取得元には触らない。
 *
 * 共有フォルダ（SMB・端末のフォルダ）は、1 回の呼び出しごとに [FolderTreeFactory] で木を作って閉じる（全走査は 1 本の接続で行い、
 * ダウンロードは [DownloadStream.close] で閉じる）。接続の寿命を呼び出しの外に持ち出さない。
 * 接続していなければ（Jellyfin の接続も無ければ）[JellyfinSource] が [SourceException.Unauthorized] を投げる。
 */
@Singleton
class SessionSourceGateway internal constructor(
    private val store: SessionStore,
    private val jellyfin: JellyfinSource,
    private val trees: FolderTreeFactory,
    private val tags: TagReader,
    private val scanned: ScannedFiles,
    private val io: CoroutineDispatcher,
) : SourceGateway {
    /** DI 用。[io] は [Dispatchers.IO]（テストは上の constructor で差し替える）。 */
    @Inject constructor(
        store: SessionStore,
        jellyfin: JellyfinSource,
        trees: FolderTreeFactory,
        tags: TagReader,
        scanned: ScannedFiles,
    ) : this(store, jellyfin, trees, tags, scanned, Dispatchers.IO)

    /** 途中経過も振り分け先に渡す（共有フォルダは番組ごとに流す）。全走査は 1 本の木（接続）で行う。 */
    override suspend fun fetchAll(listener: ScanListener): SourceSnapshot = withSource { it.fetchAll(listener) }

    override suspend fun fetchProgramEpisodes(programId: SourceItemId): List<SourceEpisode> =
        withSource { it.fetchProgramEpisodes(programId) }

    override suspend fun fetchProgram(programId: SourceItemId): SourceProgram? =
        withSource { it.fetchProgram(programId) }

    /** 番組の画像。共有フォルダは呼び出しごとに木を開く（全走査の途中で呼ばれると、走査の接続とは別にもう 1 本張る）。 */
    override suspend fun fetchProgramImage(programId: SourceItemId): ByteArray? =
        withSource { it.fetchProgramImage(programId) }

    override suspend fun openDownload(episodeId: SourceItemId, rangeStart: Long): DownloadStream {
        val tree = openTreeOrNull() ?: return jellyfin.openDownload(episodeId, rangeStart)
        // 木は成功すれば返す DownloadStream が閉じる。失敗したらここで閉じる
        return withTreeOwnership(tree) {
            val inner = sharedFolderSource(tree).openDownload(episodeId, rangeStart)
            // inner は作り直す。body は新しい DownloadStream が閉じ、続けて木（接続）を閉じる
            DownloadStream(inner.resumedFrom, inner.totalBytes, inner.body) { tree.close() }
        }
    }

    /** 各回のファイルの大きさ（ADR 0011）。共有フォルダは呼び出しごとに木を開く。 */
    override suspend fun fetchFileSize(episodeId: SourceItemId): Long? =
        withSource { it.fetchFileSize(episodeId) }

    private suspend fun <T> withSource(block: suspend (SourceGateway) -> T): T {
        val tree = openTreeOrNull() ?: return block(jellyfin)
        try {
            return block(sharedFolderSource(tree))
        } catch (e: Throwable) {
            logFailure(e)
            throw e
        } finally {
            withContext(NonCancellable + io) { tree.close() }
        }
    }

    private fun sharedFolderSource(tree: CloseableFolderTree) = SharedFolderSource(tree, tags, scanned, io)

    /** [block] が失敗したらログを残して [tree] を閉じて投げ直す。成功したら木は閉じず、持ち主を [block] の結果に任せる。 */
    private suspend fun <T> withTreeOwnership(tree: CloseableFolderTree, block: suspend () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            logFailure(e)
            withContext(NonCancellable + io) { tree.close() }
            throw e
        }

    /** 共有フォルダへの接続・読み取りの失敗を原因の連鎖付きで残す（画面に出る文言には原因が出ないため）。取り消しは失敗でない。 */
    private fun logFailure(e: Throwable) {
        if (e !is CancellationException) Log.w(TAG, "shared folder access failed", e)
    }

    private companion object {
        const val TAG = "SessionSourceGateway"
    }

    /** 今の取得元が共有フォルダ（SMB・端末のフォルダ）なら、その木を開く。Jellyfin か未接続なら null。 */
    private suspend fun openTreeOrNull(): CloseableFolderTree? =
        when (val source = (store.current() as? SessionState.Ready)?.source) {
            is ConnectedSource.Smb -> trees.open(source.connection)
            is ConnectedSource.Device -> trees.open(source.folder)
            is ConnectedSource.Jellyfin, null -> null
        }
}
