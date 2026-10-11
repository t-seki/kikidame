package dev.tseki.kikidame.data.repository

import androidx.test.core.app.ApplicationProvider
import dev.tseki.kikidame.data.db.LocalFileEntity
import dev.tseki.kikidame.data.device.MemoryDeviceDocuments
import dev.tseki.kikidame.data.files.EpisodesDirectory
import dev.tseki.kikidame.data.jellyfin.JellyfinSource
import dev.tseki.kikidame.data.source.SourceGateway
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.SourceItemId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * [RoomTestBase] に、取り込み（[RoomLibraryRefreshRepository]）までの配線を足したもの。取得元は [refreshRepository] に渡す。
 * 接続の記録（[store]）は一時フォルダに置き、テストの後に [scope] を閉じる。
 */
abstract class RefreshTestBase : RoomTestBase() {
    @get:Rule
    val tmp = TemporaryFolder()

    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    protected val store by lazy { testSessionStore(tmp.root, scope) }
    protected val directory by lazy { EpisodesDirectory(ApplicationProvider.getApplicationContext()) }
    protected val downloads by lazy { RoomDownloadRepository(db, directory, clock) }
    protected val library by lazy { RoomLibraryRepository(db.programDao(), db.episodeDao(), db.localFileDao()) }
    protected val playback by lazy { RoomPlaybackStateRepository(db, clock) }

    @After
    fun cancelScope() = scope.cancel()

    protected fun refreshRepository(source: SourceGateway) = RoomLibraryRefreshRepository(db, store, source, downloads, clock)

    /**
     * 取得元の回。[published] は日付だけ（その日の 0 時 UTC）か Instant の形。
     * 取り込まれた日時（addedAt）は固定、大きさは不明、コンテナは m4a。
     */
    protected fun sourceEpisode(
        id: String,
        title: String,
        published: String,
        program: String = "album-1",
        runtime: Duration = 90.minutes,
    ) = SourceEpisode(
        sourceId = SourceItemId(id),
        programSourceId = SourceItemId(program),
        title = title,
        publishedAt = Instant.parse(if ('T' in published) published else "${published}T00:00:00Z"),
        addedAt = Instant.parse("2026-09-16T02:00:00Z"),
        runtime = runtime,
        sizeBytes = null,
        container = "m4a",
    )

    /**
     * 回 [episodeId] を [file] に落とし終えた（DONE）ことにする（ダウンロード完了の代わり）。
     * [bytes] があれば [file] を作ってその中身を書く。null なら行だけで、ファイルは置かない。
     */
    protected suspend fun markDoneAt(episodeId: Long, file: File, pinned: Boolean, bytes: ByteArray? = ByteArray(8)): File {
        bytes?.let { file.parentFile?.mkdirs(); file.writeBytes(it) }
        db.localFileDao().upsert(LocalFileEntity(episodeId, DownloadState.DONE, file.absolutePath, pinned = pinned, downloadedAt = now))
        return file
    }
}

/** 取得元が Jellyfin（[FakeJellyfinGateway]）の [RefreshTestBase]。[repo] は Jellyfin から取り込む。 */
abstract class JellyfinRefreshTestBase : RefreshTestBase() {
    protected val gateway = FakeJellyfinGateway()
    protected val session by lazy { DataStoreSessionRepository(store, gateway, FakeFolderTreeFactory(), MemoryDeviceDocuments()) }
    protected val repo by lazy { refreshRepository(JellyfinSource(gateway, store)) }

    protected suspend fun signInAndSelect() {
        session.signIn("jellyfin.lab.example", "alice", "secret")
        session.selectLibrary(LibraryView(SourceItemId("lib-1"), "Radio", "music", isMusic = true))
    }
}
