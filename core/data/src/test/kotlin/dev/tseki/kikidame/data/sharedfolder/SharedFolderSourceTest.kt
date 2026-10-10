package dev.tseki.kikidame.data.sharedfolder

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.data.db.LocalFileEntity
import dev.tseki.kikidame.data.files.EpisodesDirectory
import dev.tseki.kikidame.data.repository.RoomDownloadRepository
import dev.tseki.kikidame.data.repository.RoomLibraryRefreshRepository
import dev.tseki.kikidame.data.repository.RoomTestBase
import dev.tseki.kikidame.data.repository.testSessionStore
import dev.tseki.kikidame.domain.DownloadState
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceProgram
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/**
 * 共有フォルダの走査（#197、epic #195 の決定 5・7・8・9）をメモリ上の木で確かめる。
 * タグの読み取りはフェイク（[FakeTagReader]）。前の走査の結果は Room の回の行から読む（[RoomScannedFiles]）。
 */
@RunWith(AndroidJUnit4::class)
class SharedFolderSourceTest : RoomTestBase() {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val folder = MemoryFolderTree()
    private val tags = FakeTagReader()
    private val source by lazy { SharedFolderSource(folder.tree, tags, RoomScannedFiles(db.episodeDao())) }
    private val store by lazy { testSessionStore(tmp.root, scope) }
    private val directory by lazy { EpisodesDirectory(ApplicationProvider.getApplicationContext()) }
    private val downloads by lazy { RoomDownloadRepository(db, directory, clock) }
    private val repo by lazy { RoomLibraryRefreshRepository(db, store, source, downloads, clock) }

    @After
    fun tearDown() = scope.cancel()

    /** 木を読んでからタグを返す（木の失敗はそのまま投げる。[TagReader] の約束）。読んだパスを記録する。 */
    class FakeTagReader : TagReader {
        val tagsByPath = HashMap<String, AudioTags>()
        val calls = ArrayList<String>()
        override fun read(tree: FolderTree, path: String, sizeBytes: Long): AudioTags {
            calls += path
            tree.read(path, 0, ByteArray(4), 0, 4)
            return tagsByPath[path] ?: AudioTags()
        }
    }

    private suspend fun importAll() = repo.apply(source.fetchAll())

    private fun SourceEpisode.id() = sourceId.value

    @Test
    fun onlyAudioFilesOnTheThirdLevelAreEpisodes() = runTest {
        folder.put("readme.txt")
        folder.put("TBSラジオ/notes.mp3")
        folder.put("TBSラジオ/ハライチのターン！/2026-09-18.m4a")
        folder.put("TBSラジオ/ハライチのターン！/2026-09-25.MP3")
        folder.put("TBSラジオ/ハライチのターン！/cover.jpg")
        folder.put("TBSラジオ/ハライチのターン！/extra/2026-09-11.m4a")
        folder.put("TBSラジオ/ハライチのターン！/no-extension")
        folder.emptyDir("文化放送/空の番組")

        val snapshot = source.fetchAll()

        assertEquals(
            listOf(
                SourceProgram(SourceItemId("TBSラジオ/ハライチのターン！"), "ハライチのターン！", "TBSラジオ"),
                SourceProgram(SourceItemId("文化放送/空の番組"), "空の番組", "文化放送"),
            ),
            snapshot.programs,
        )
        assertEquals(
            listOf("TBSラジオ/ハライチのターン！/2026-09-18.m4a", "TBSラジオ/ハライチのターン！/2026-09-25.MP3"),
            snapshot.episodes.map { it.id() },
        )
        assertEquals(listOf("m4a", "mp3"), snapshot.episodes.map { it.container })
        assertEquals(setOf(SourceItemId("TBSラジオ/ハライチのターン！")), snapshot.episodes.map { it.programSourceId }.toSet())
    }

    @Test
    fun programFetchAnswersFromThatFolderOnly() = runTest {
        folder.put("TBSラジオ/A/1.m4a")
        folder.put("TBSラジオ/B/2.m4a")

        val program = source.fetchProgram(SourceItemId("TBSラジオ/A"))
        val episodes = source.fetchProgramEpisodes(SourceItemId("TBSラジオ/A"))

        assertEquals(SourceProgram(SourceItemId("TBSラジオ/A"), "A", "TBSラジオ"), program)
        assertEquals(listOf("TBSラジオ/A/1.m4a"), episodes.map { it.id() })
    }

    @Test
    fun missingTagsAreFilledFromTheFile() = runTest {
        // JST では 2026-09-18 01:00
        val modified = Instant.parse("2026-09-17T16:00:00Z")
        folder.put("TBSラジオ/番組/第1回.m4a", ByteArray(100), modified)

        val e = source.fetchAll().episodes.single()

        assertEquals("第1回", e.title)
        assertEquals(Instant.parse("2026-09-17T15:00:00Z"), e.publishedAt, "file date in JST at 00:00")
        assertEquals(modified, e.addedAt)
        assertEquals(Duration.ZERO, e.runtime)
        assertEquals(emptyList(), e.performers)
        assertEquals(100L, e.sizeBytes)
        assertEquals(100L, e.sourceFileSize)
        assertEquals(modified, e.sourceModifiedAt)
    }

    @Test
    fun tagsAreUsedWhenPresent() = runTest {
        folder.put("TBSラジオ/番組/a.m4a", modifiedAt = Instant.parse("2026-09-20T00:00:00Z"))
        tags.tagsByPath["TBSラジオ/番組/a.m4a"] = AudioTags(title = "初回スペシャル", date = "2026-09-10", artist = "岩井勇気", duration = 90.minutes)

        val e = source.fetchAll().episodes.single()

        assertEquals("初回スペシャル", e.title)
        assertEquals(Instant.parse("2026-09-09T15:00:00Z"), e.publishedAt)
        assertEquals(90.minutes, e.runtime)
        assertEquals(listOf("岩井勇気"), e.performers)
    }

    @Test
    fun yearOnlyOrUnreadableDatesFallBackToTheFileDate() = runTest {
        val modified = Instant.parse("2026-09-17T16:00:00Z")
        folder.put("P/番組/year.m4a", modifiedAt = modified)
        folder.put("P/番組/junk.m4a", modifiedAt = modified)
        tags.tagsByPath["P/番組/year.m4a"] = AudioTags(date = "2026")
        tags.tagsByPath["P/番組/junk.m4a"] = AudioTags(date = "そのうち")

        val episodes = source.fetchAll().episodes

        assertEquals(listOf(Instant.parse("2026-09-17T15:00:00Z")), episodes.map { it.publishedAt }.distinct())
    }

    @Test
    fun unchangedFilesAreNotReadAgain() = runTest {
        // ms より細かい更新日時が来ても（SMB の実装から何が来るかは #198 で確かめる。未確認）、Room は ms で持つので同じとみなすこと
        val modified = Instant.parse("2026-09-17T16:00:00Z") + 1234.nanoseconds
        folder.put("P/番組/a.m4a", ByteArray(10), modified)
        tags.tagsByPath["P/番組/a.m4a"] = AudioTags(title = "タグの題", duration = 30.minutes)
        importAll()
        tags.calls.clear()
        // 中身のタグが変わっても、パス・サイズ・更新日時が同じなら読み直さずに前の値を使う
        tags.tagsByPath["P/番組/a.m4a"] = AudioTags(title = "変わった題")

        val e = source.fetchAll().episodes.single()

        assertEquals(emptyList(), tags.calls)
        assertEquals("タグの題", e.title)
        assertEquals(30.minutes, e.runtime)
        assertEquals("タグの題", source.fetchProgramEpisodes(SourceItemId("P/番組")).single().title)
        assertEquals(emptyList(), tags.calls, "the program fetch does not read tags either")
    }

    @Test
    fun aChangedSizeOrModifiedTimeReadsTagsAgain() = runTest {
        val modified = Instant.parse("2026-09-17T16:00:00Z")
        folder.put("P/番組/size.m4a", ByteArray(10), modified)
        folder.put("P/番組/time.m4a", ByteArray(10), modified)
        folder.put("P/番組/same.m4a", ByteArray(10), modified)
        importAll()
        tags.calls.clear()

        folder.put("P/番組/size.m4a", ByteArray(11), modified)
        folder.put("P/番組/time.m4a", ByteArray(10), modified + 1.minutes)
        tags.tagsByPath["P/番組/size.m4a"] = AudioTags(title = "新しい題")
        source.fetchAll()

        assertEquals(listOf("P/番組/size.m4a", "P/番組/time.m4a"), tags.calls.sorted())
    }

    @Test
    fun aRenamedFileIsReadAsANewPath() = runTest {
        folder.put("P/番組/a.m4a")
        importAll()
        tags.calls.clear()

        folder.rename("P/番組/a.m4a", "P/番組/b.m4a")
        source.fetchAll()

        assertEquals(listOf("P/番組/b.m4a"), tags.calls)
    }

    @Test
    fun aFailingTreeIsUnreachable() = runTest {
        folder.put("P/番組/a.m4a")
        folder.failing = true

        assertFailsWith<ServerException.Unreachable> { source.fetchAll() }
        assertFailsWith<ServerException.Unreachable> { source.fetchProgram(SourceItemId("P/番組")) }
        assertFailsWith<ServerException.Unreachable> { source.fetchProgramEpisodes(SourceItemId("P/番組")) }
    }

    @Test
    fun aFailingPartialReadIsUnreachableNotMissingTags() = runTest {
        folder.put("P/番組/a.m4a")
        val failingReads = SharedFolderSource(
            tree = object : FolderTree by folder.tree {
                override fun read(path: String, position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
                    throw java.io.IOException("connection reset")
            },
            tags = tags,
            scanned = RoomScannedFiles(db.episodeDao()),
        )

        assertFailsWith<ServerException.Unreachable> { failingReads.fetchAll() }
    }

    @Test
    fun aMissingRootIsUnreachableNotAnEmptyLibrary() = runTest {
        val empty = SharedFolderSource(
            tree = object : FolderTree by folder.tree {
                override fun list(path: String): List<FolderEntry>? = null
            },
            tags = tags,
            scanned = RoomScannedFiles(db.episodeDao()),
        )

        assertFailsWith<ServerException.Unreachable> { empty.fetchAll() }
    }

    @Test
    fun aMissingProgramFolderIsGone() = runTest {
        folder.put("P/番組/a.m4a")

        assertNull(source.fetchProgram(SourceItemId("P/無い番組")))
        assertNull(source.fetchProgram(SourceItemId("P/番組/a.m4a")), "not a <publisher>/<program> id")
        assertEquals(emptyList(), source.fetchProgramEpisodes(SourceItemId("P/無い番組")))
    }

    @Test
    fun downloadsResumeFromTheOffset() = runTest {
        folder.put("P/番組/a.m4a", byteArrayOf(1, 2, 3, 4, 5))

        val whole = source.openDownload(SourceItemId("P/番組/a.m4a"), 0)
        val rest = source.openDownload(SourceItemId("P/番組/a.m4a"), 3)

        assertNull(whole.resumedFrom)
        assertEquals(5L, whole.totalBytes)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), whole.body.readBytes().toList())
        assertEquals(3L, rest.resumedFrom)
        assertEquals(5L, rest.totalBytes)
        assertEquals(listOf<Byte>(4, 5), rest.body.readBytes().toList())
        val missing = runCatching { source.openDownload(SourceItemId("P/番組/b.m4a"), 0) }.exceptionOrNull()
        assertIs<ServerException.Failed>(missing)
    }

    // --- ファイル名を変えたときの突合（epic #195 の決定 7） ---

    private suspend fun markDone(sourceId: String): String {
        val row = db.episodeDao().findBySourceItemId(sourceId)!!
        val path = "${directory.root}/P/番組/${row.title}.m4a"
        db.localFileDao().upsert(LocalFileEntity(row.id, DownloadState.DONE, path, pinned = true, downloadedAt = now))
        return path
    }

    @Test
    fun aRenamedFileWithATitleTagRelinksByTitle() = runTest {
        folder.put("P/番組/0918.m4a", ByteArray(10))
        tags.tagsByPath["P/番組/0918.m4a"] = AudioTags(title = "第1回", date = "2026-09-18", duration = 30.minutes)
        tags.tagsByPath["P/番組/renamed.m4a"] = AudioTags(title = "第1回", date = "2026-09-18", duration = 30.minutes)
        importAll()
        val before = db.episodeDao().findBySourceItemId("P/番組/0918.m4a")!!
        val path = markDone("P/番組/0918.m4a")

        folder.rename("P/番組/0918.m4a", "P/番組/renamed.m4a")
        val snapshot = source.fetchAll()
        val result = repo.apply(snapshot)
        val outcome = repo.synchronize(snapshot, excluded = emptySet())

        assertEquals(1, result.linkedEpisodes)
        assertEquals(0, outcome.removed)
        val after = db.episodeDao().findById(before.id)!!
        assertEquals("P/番組/renamed.m4a", after.episode.sourceItemId)
        assertEquals("第1回", after.episode.title)
        assertEquals(path, after.localFile!!.path, "the local copy keeps its path")
    }

    @Test
    fun aRenamedFileWithoutATitleTagRelinksByDateAndRuntime() = runTest {
        val modified = Instant.parse("2026-09-17T16:00:00Z")
        folder.put("P/番組/0918.m4a", ByteArray(10), modified)
        tags.tagsByPath["P/番組/0918.m4a"] = AudioTags(duration = 30.minutes)
        tags.tagsByPath["P/番組/ハライチ 0918.m4a"] = AudioTags(duration = 30.minutes)
        importAll()
        val before = db.episodeDao().findBySourceItemId("P/番組/0918.m4a")!!
        val path = markDone("P/番組/0918.m4a")

        folder.rename("P/番組/0918.m4a", "P/番組/ハライチ 0918.m4a")
        val snapshot = source.fetchAll()
        val result = repo.apply(snapshot)
        val outcome = repo.synchronize(snapshot, excluded = emptySet())

        assertEquals(1, result.linkedEpisodes)
        assertEquals(0, result.newEpisodes)
        assertEquals(0, outcome.removed)
        val after = db.episodeDao().findById(before.id)!!
        assertEquals("P/番組/ハライチ 0918.m4a", after.episode.sourceItemId)
        assertEquals("ハライチ 0918", after.episode.title, "title follows the new file name")
        assertEquals(path, after.localFile!!.path, "the local copy keeps its path")
    }
}
