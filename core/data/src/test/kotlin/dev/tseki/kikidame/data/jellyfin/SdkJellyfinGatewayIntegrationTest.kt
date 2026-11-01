package dev.tseki.kikidame.data.jellyfin

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.domain.LibraryKind
import dev.tseki.kikidame.domain.PublishedAt
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.SharedPlayState
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.SourceException
import dev.tseki.kikidame.domain.SourceItemId
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * 実サーバに対する [SdkJellyfinGateway] の統合テスト。`scripts/jellyfin-testserver.sh up 1010|12` で立てた
 * 合成ライブラリ（音楽の `radio` は番組 2 × 各回 3、ブックの `radio-book` はそれに各回が 1 本だけの番組 C を足したもの）を前提に、
 * [JellyfinGateway] の全メソッドを、音楽とブックのライブラリでそれぞれ 1 回ずつ呼ぶ。
 * ブックの各回の UserData の書き込みと読み戻し（再生の共有）も見る。
 *
 * 環境変数 `KIKIDAME_JELLYFIN_URL`（と `KIKIDAME_JELLYFIN_USER` / `KIKIDAME_JELLYFIN_PASSWORD`、既定は
 * スクリプトと同じ `kikidame` / `kikidame-test`）が無ければスキップするので、CI と普段の `./gradlew test` には影響しない:
 *
 *   KIKIDAME_JELLYFIN_URL=http://localhost:8097 ./gradlew :core:data:testDebugUnitTest \
 *     --tests dev.tseki.kikidame.data.jellyfin.SdkJellyfinGatewayIntegrationTest
 *
 * Robolectric なのは [SdkJellyfinGateway] が SDK の初期化に Android の `Context` を要るため。通信は本物。
 */
@RunWith(AndroidJUnit4::class)
class SdkJellyfinGatewayIntegrationTest {
    private val url = System.getenv("KIKIDAME_JELLYFIN_URL")?.trimEnd('/')
    private val user = System.getenv("KIKIDAME_JELLYFIN_USER") ?: "kikidame"
    private val password = System.getenv("KIKIDAME_JELLYFIN_PASSWORD") ?: "kikidame-test"
    private lateinit var gateway: SdkJellyfinGateway

    @Before
    fun setUp() {
        assumeTrue("KIKIDAME_JELLYFIN_URL が無いのでスキップ", url != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        // SDK は DeviceId を ANDROID_ID から、Device を端末名から作る。Robolectric では両方 null なので埋める
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "robolectric-integration-test")
        Settings.Global.putString(context.contentResolver, "device_name", "robolectric")
        gateway = SdkJellyfinGateway(context)
    }

    private suspend fun signIn(): ServerCredentials {
        val session = gateway.signIn(url!!, user, password)
        assertEquals(user, session.userName)
        assertTrue(session.accessToken.isNotBlank())
        assertTrue(session.userId.isNotBlank())
        return session.credentials()
    }

    private suspend fun radio(credentials: ServerCredentials): SelectedLibrary {
        val libraries = gateway.listLibraries(credentials)
        val radio = libraries.firstOrNull { it.name == "radio" }
        assertNotNull(radio, "ライブラリ radio が無い: $libraries")
        assertEquals(LibraryKind.MUSIC, radio.kind, "radio は音楽ライブラリのはず: $radio")
        assertEquals("music", radio.collectionType)
        return SelectedLibrary(radio.id, radio.name, LibraryKind.MUSIC)
    }

    private suspend fun radioBook(credentials: ServerCredentials): SelectedLibrary {
        val libraries = gateway.listLibraries(credentials)
        val book = libraries.firstOrNull { it.name == "radio-book" }
        assertNotNull(book, "ライブラリ radio-book が無い: $libraries")
        assertEquals(LibraryKind.BOOKS, book.kind, "radio-book はブックのライブラリのはず: $book")
        assertEquals("books", book.collectionType)
        return SelectedLibrary(book.id, book.name, LibraryKind.BOOKS)
    }

    @Test
    fun libraryHasTwoProgramsAndSixEpisodes() = runTest {
        val credentials = signIn()
        val snapshot = gateway.fetchLibrary(credentials, radio(credentials))

        assertEquals(setOf("テスト番組A", "テスト番組B"), snapshot.programs.map { it.name }.toSet())
        assertEquals("ニッポン放送", snapshot.programs.first { it.name == "テスト番組A" }.publisherName)
        assertEquals("TBSラジオ", snapshot.programs.first { it.name == "テスト番組B" }.publisherName)

        assertEquals(6, snapshot.episodes.size)
        val programIds = snapshot.programs.map { it.sourceId }.toSet()
        assertTrue(snapshot.episodes.all { it.programSourceId in programIds }, "番組に属さない各回が混ざっている")
        val byTitle = snapshot.episodes.associateBy { it.title }
        assertEquals(setOf("2026-09-14", "2026-09-14 (1)", "夏休みスペシャル", "2026-09-07", "2026-08-31", "2026-08-24"), byTitle.keys)

        // 公開日はタグ（PremiereDate）から。タイトルが日付でない回も、同じ日の 2 本目も
        fun day(y: Int, m: Int, d: Int) = LocalDate(y, m, d).atStartOfDayIn(PublishedAt.ZONE)
        assertEquals(day(2026, 8, 1), byTitle.getValue("夏休みスペシャル").publishedAt)
        assertEquals(day(2026, 9, 14), byTitle.getValue("2026-09-14").publishedAt)
        assertEquals(day(2026, 9, 14), byTitle.getValue("2026-09-14 (1)").publishedAt)
        assertEquals(day(2026, 8, 31), byTitle.getValue("2026-08-31").publishedAt)

        // 尺・取り込み日時・コンテナ（サイズは MediaSources を避けるので null のまま。ダウンロードで確定する）
        for (e in snapshot.episodes) {
            assertEquals(12.seconds, e.runtime, "尺: ${e.title}")
            assertNotNull(e.addedAt, "取り込み日時: ${e.title}")
            assertNull(e.sizeBytes, "サイズは一覧では持たない: ${e.title}")
            assertTrue("m4a" in e.container.split(','), "コンテナ: ${e.title} -> ${e.container}")
        }

        // 出演者（Artists）: 無い回は空、ある回はサーバの表記のまま
        assertEquals(emptyList(), byTitle.getValue("2026-08-31").performers)
        assertEquals(listOf("出演者丙"), byTitle.getValue("2026-09-07").performers)
        assertTrue(byTitle.getValue("2026-09-14").performers.any { "出演者甲" in it }, "出演者: ${byTitle.getValue("2026-09-14").performers}")
    }

    @Test
    fun programEpisodesAreScopedToThatProgram() = runTest {
        val credentials = signIn()
        val music = radio(credentials)
        val snapshot = gateway.fetchLibrary(credentials, music)
        val programA = snapshot.programs.first { it.name == "テスト番組A" }

        val episodes = gateway.fetchProgramEpisodes(credentials, music, programA.sourceId)
        assertEquals(setOf("2026-09-14", "2026-09-14 (1)", "夏休みスペシャル"), episodes.map { it.title }.toSet())
        assertTrue(episodes.all { it.programSourceId == programA.sourceId })

        val program = gateway.fetchProgram(credentials, music, programA.sourceId)
        assertNotNull(program)
        assertEquals("テスト番組A", program.name)
        assertEquals("ニッポン放送", program.publisherName)

        // サーバに無い番組は null（消失）。各回の ID を番組として引いても null
        assertNull(gateway.fetchProgram(credentials, music, SourceItemId(UUID.randomUUID().toString())))
        assertNull(gateway.fetchProgram(credentials, music, episodes.first().sourceId))
        // ブックのライブラリの番組 ID（UUID でない。ブックから音楽へ選び直した直後など）も、失敗にせず無い番組として扱う
        assertNull(gateway.fetchProgram(credentials, music, SourceItemId("ニッポン放送/テスト番組A")))
        assertEquals(emptyList(), gateway.fetchProgramEpisodes(credentials, music, SourceItemId("ニッポン放送/テスト番組A")))
        assertNull(gateway.fetchProgramImage(credentials, music, SourceItemId("ニッポン放送/テスト番組A")))
    }

    /** 番組の画像: `folder.jpg` のある番組 A だけ Primary の ImageTag を持ち、中身が JPEG で取れる。番組 B は画像なし。 */
    @Test
    fun programImageComesFromThePrimaryImage() = runTest {
        val credentials = signIn()
        val music = radio(credentials)
        val snapshot = gateway.fetchLibrary(credentials, music)
        val programA = snapshot.programs.first { it.name == "テスト番組A" }
        val programB = snapshot.programs.first { it.name == "テスト番組B" }

        val tag = assertNotNull(programA.image, "ImageTag が一覧で返らない").version
        assertTrue(tag.isNotBlank())
        assertNull(programB.image)
        // 1 番組の取得でも同じ版
        assertEquals(tag, gateway.fetchProgram(credentials, music, programA.sourceId)?.image?.version)

        val bytes = assertNotNull(gateway.fetchProgramImage(credentials, music, programA.sourceId))
        assertTrue(bytes.isJpeg(), "JPEG の先頭ではない: ${bytes.size} bytes")
        assertNull(gateway.fetchProgramImage(credentials, music, programB.sourceId), "画像の無い番組は null")
        // 10.10.7 の画像の API は、無効なトークンでも返す（認証を求めない）ので、Unauthorized は確かめない
    }

    @Test
    fun downloadStreamsTheOriginalAndHonoursRange() = runTest {
        val credentials = signIn()
        val snapshot = gateway.fetchLibrary(credentials, radio(credentials))
        val episode = snapshot.episodes.first { it.title == "2026-09-07" }
        assertDownloads(credentials, episode)
    }

    private suspend fun assertDownloads(credentials: ServerCredentials, episode: SourceEpisode) {
        val whole = gateway.openDownload(credentials, episode.sourceId, rangeStart = 0)
        val total = whole.use { stream ->
            assertNull(stream.resumedFrom)
            val bytes = stream.body.readBytes()
            assertTrue(bytes.size > 1_000, "本文が短すぎる: ${bytes.size}")
            assertEquals(bytes.size.toLong(), stream.totalBytes, "Content-Length と本文の長さ")
            bytes.size.toLong()
        }

        // 差し替え（ADR 0011）: 一覧は Etag をファイルの版として返し、大きさは中身を落とさずに取れる
        assertTrue(!episode.version.isNullOrBlank(), "一覧に Etag が無い")
        assertEquals(total, gateway.fetchFileSize(credentials, episode.sourceId), "Range: bytes=0-0 の全長と本文の長さ")

        gateway.openDownload(credentials, episode.sourceId, rangeStart = 100).use { stream ->
            val rest = stream.body.readBytes()
            if (stream.resumedFrom != null) {
                // Range が効いた: 続きだけ返り、全体の長さは変わらない
                assertEquals(100L, stream.resumedFrom)
                assertEquals(total - 100, rest.size.toLong())
                assertEquals(total, stream.totalBytes)
            } else {
                // サーバが Range を無視して全体を返した
                assertEquals(total, rest.size.toLong())
            }
        }
    }

    /**
     * ブックのライブラリ（ADR 0010 の決定 2）: 各回は AudioBook で、番組は Album と AlbumArtist のタグでまとまる。
     * 番組の取得元 ID は `<AlbumArtist>/<Album>`。各回が 1 本だけの番組 C もまとまる。
     */
    @Test
    fun bookLibraryGroupsAudioBooksIntoProgramsByTheirTags() = runTest {
        val credentials = signIn()
        val snapshot = gateway.fetchLibrary(credentials, radioBook(credentials))

        assertEquals(
            setOf("ニッポン放送/テスト番組A", "TBSラジオ/テスト番組B", "ニッポン放送/テスト番組C"),
            snapshot.programs.map { it.sourceId.value }.toSet(),
        )
        val programs = snapshot.programs.associateBy { it.name }
        assertEquals("ニッポン放送", programs.getValue("テスト番組A").publisherName)
        assertEquals("TBSラジオ", programs.getValue("テスト番組B").publisherName)
        assertEquals("ニッポン放送", programs.getValue("テスト番組C").publisherName)

        assertEquals(7, snapshot.episodes.size)
        val byTitle = snapshot.episodes.associateBy { it.title }
        assertEquals(
            setOf("2026-09-14", "2026-09-14 (1)", "夏休みスペシャル", "2026-09-07", "2026-08-31", "2026-08-24", "2026-09-01"),
            byTitle.keys,
        )
        assertEquals(SourceItemId("ニッポン放送/テスト番組A"), byTitle.getValue("夏休みスペシャル").programSourceId)
        assertEquals(SourceItemId("TBSラジオ/テスト番組B"), byTitle.getValue("2026-08-31").programSourceId)
        assertEquals(SourceItemId("ニッポン放送/テスト番組C"), byTitle.getValue("2026-09-01").programSourceId)

        fun day(y: Int, m: Int, d: Int) = LocalDate(y, m, d).atStartOfDayIn(PublishedAt.ZONE)
        assertEquals(day(2026, 8, 1), byTitle.getValue("夏休みスペシャル").publishedAt)
        assertEquals(day(2026, 9, 14), byTitle.getValue("2026-09-14 (1)").publishedAt)
        assertEquals(day(2026, 9, 1), byTitle.getValue("2026-09-01").publishedAt)
        for (e in snapshot.episodes) {
            assertEquals(12.seconds, e.runtime, "尺: ${e.title}")
            assertNotNull(e.addedAt, "取り込み日時: ${e.title}")
            assertNull(e.sizeBytes, "サイズは一覧では持たない: ${e.title}")
            // AudioBook は Container を返さないので、Path の拡張子から
            assertEquals("m4a", e.container, "コンテナ: ${e.title}")
        }
        assertEquals(emptyList(), byTitle.getValue("2026-08-31").performers)
        assertEquals(listOf("出演者丁"), byTitle.getValue("2026-09-01").performers)
    }

    @Test
    fun bookProgramEpisodesAreScopedToThatProgram() = runTest {
        val credentials = signIn()
        val book = radioBook(credentials)
        val programA = SourceItemId("ニッポン放送/テスト番組A")
        val programC = SourceItemId("ニッポン放送/テスト番組C")

        val episodes = gateway.fetchProgramEpisodes(credentials, book, programA)
        assertEquals(setOf("2026-09-14", "2026-09-14 (1)", "夏休みスペシャル"), episodes.map { it.title }.toSet())
        assertTrue(episodes.all { it.programSourceId == programA })
        assertEquals(listOf("2026-09-01"), gateway.fetchProgramEpisodes(credentials, book, programC).map { it.title })

        val program = assertNotNull(gateway.fetchProgram(credentials, book, programA))
        assertEquals("テスト番組A", program.name)
        assertEquals("ニッポン放送", program.publisherName)
        assertEquals("テスト番組C", gateway.fetchProgram(credentials, book, programC)?.name)

        // 無い番組は null（消失）。音楽のライブラリの番組 ID（MusicAlbum の id）を引いても null
        assertNull(gateway.fetchProgram(credentials, book, SourceItemId("ニッポン放送/無い番組")))
        assertEquals(emptyList(), gateway.fetchProgramEpisodes(credentials, book, SourceItemId("ニッポン放送/無い番組")))
        val musicAlbum = gateway.fetchLibrary(credentials, radio(credentials)).programs.first { it.name == "テスト番組A" }.sourceId
        assertNull(gateway.fetchProgram(credentials, book, musicAlbum))
    }

    /**
     * ブックのライブラリの番組の画像は、番組のフォルダ（Folder）の Primary。`folder.jpg` のある番組 A だけ画像を持つ。
     * 各回が 1 本だけの番組 C は、各回の親が配信元のフォルダ（`folder.jpg` を置いてある）になるが、それを番組 C の画像にしない。
     */
    @Test
    fun bookProgramImageComesFromTheProgramFolder() = runTest {
        val credentials = signIn()
        val book = radioBook(credentials)
        val programs = gateway.fetchLibrary(credentials, book).programs.associateBy { it.name }

        val tag = assertNotNull(programs.getValue("テスト番組A").image, "番組のフォルダの ImageTag が返らない").version
        assertNull(programs.getValue("テスト番組B").image)
        assertNull(programs.getValue("テスト番組C").image, "配信元のフォルダの画像を番組の画像にしない")
        assertEquals(tag, gateway.fetchProgram(credentials, book, SourceItemId("ニッポン放送/テスト番組A"))?.image?.version)

        val bytes = assertNotNull(gateway.fetchProgramImage(credentials, book, SourceItemId("ニッポン放送/テスト番組A")))
        assertTrue(bytes.isJpeg(), "JPEG の先頭ではない: ${bytes.size} bytes")
        assertNull(gateway.fetchProgramImage(credentials, book, SourceItemId("TBSラジオ/テスト番組B")))
        assertNull(gateway.fetchProgramImage(credentials, book, SourceItemId("ニッポン放送/テスト番組C")))

        // 一覧を取っていない（番組のフォルダを覚えていない）ゲートウェイでも取れる
        val fresh = SdkJellyfinGateway(ApplicationProvider.getApplicationContext())
        assertContentEquals(bytes, fresh.fetchProgramImage(credentials, book, SourceItemId("ニッポン放送/テスト番組A")))
    }

    @Test
    fun bookDownloadStreamsTheOriginalAndHonoursRange() = runTest {
        val credentials = signIn()
        val snapshot = gateway.fetchLibrary(credentials, radioBook(credentials))
        assertDownloads(credentials, snapshot.episodes.first { it.title == "2026-09-07" })
        assertDownloads(credentials, snapshot.episodes.first { it.title == "2026-09-01" })
    }

    /**
     * 再生の共有（ADR 0010 の決定 3）: ブックの各回の UserData に `POST /UserItems/{itemId}/UserData` で書いた位置・再生済み・
     * `LastPlayedDate` が、そのまま読み戻せる。5 分未満の位置（テストのファイルは 12 秒なので尺の中の 7 秒）も 0 に戻されずに残る。
     * 書いた後の値は、書き込みの応答・1 件の読み込み・一覧（同期で読む経路）のどれでも同じ（「最後に見たサーバの値」と比べるため）。
     */
    @Test
    fun bookUserDataKeepsWrittenPositionPlayedAndLastPlayedDate() = runTest {
        val credentials = signIn()
        val book = radioBook(credentials)
        val episode = gateway.fetchLibrary(credentials, book).episodes.first { it.title == "2026-08-24" }
        try {
            val at = Instant.parse("2026-10-27T01:02:03.456Z")
            val written = gateway.writePlayState(credentials, episode.sourceId, SharedPlayState(7.seconds, played = false, lastPlayedAt = at))
            assertEquals(SharedPlayState(7.seconds, played = false, lastPlayedAt = at), written, "書き込みの応答")
            assertEquals(written, gateway.fetchPlayState(credentials, episode.sourceId), "1 件の読み込み")
            assertEquals(written, gateway.fetchPlayState(credentials, episode.sourceId, timeout = 5.seconds), "時間の上限つきの読み込み")
            assertEquals(written, gateway.fetchLibrary(credentials, book).episodes.first { it.sourceId == episode.sourceId }.shared, "一覧")

            // 再生済みにする（位置はそのまま送る）。未再生に戻すのも同じ書き方
            val later = Instant.parse("2026-10-27T02:00:00Z")
            val played = gateway.writePlayState(credentials, episode.sourceId, SharedPlayState(7.seconds, played = true, lastPlayedAt = later))
            assertEquals(SharedPlayState(7.seconds, played = true, lastPlayedAt = later), played)
            assertEquals(played, gateway.fetchPlayState(credentials, episode.sourceId))
            val unplayed = gateway.writePlayState(credentials, episode.sourceId, SharedPlayState(3.seconds, played = false, lastPlayedAt = later + 1.seconds))
            assertEquals(SharedPlayState(3.seconds, played = false, lastPlayedAt = later + 1.seconds), unplayed)
            assertEquals(unplayed, gateway.fetchLibrary(credentials, book).episodes.first { it.sourceId == episode.sourceId }.shared)
        } finally {
            gateway.writePlayState(credentials, episode.sourceId, SharedPlayState(Duration.ZERO, played = false, lastPlayedAt = null))
        }
    }

    /** 再生の共有は音楽のライブラリではしない（ADR 0010 の決定 1）。一覧は UserData を読まない。 */
    @Test
    fun musicLibraryDoesNotCarryUserData() = runTest {
        val credentials = signIn()
        assertTrue(gateway.fetchLibrary(credentials, radio(credentials)).episodes.all { it.shared == null })
    }

    private fun ByteArray.isJpeg(): Boolean = size > 2 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()

    @Test
    fun badCredentialsAreUnauthorized() = runTest {
        assertFailsWith<SourceException.Unauthorized> { gateway.signIn(url!!, user, "wrong-$password") }

        val credentials = signIn()
        val bogus = credentials.copy(accessToken = "0123456789abcdef0123456789abcdef")
        assertFailsWith<SourceException.Unauthorized> { gateway.listLibraries(bogus) }
        val episode = gateway.fetchLibrary(credentials, radio(credentials)).episodes.first()
        assertFailsWith<SourceException.Unauthorized> { gateway.openDownload(bogus, episode.sourceId, 0) }
    }

    @Test
    fun closedPortIsUnreachable() = runTest {
        assertFailsWith<SourceException.Unreachable> { gateway.signIn("http://127.0.0.1:1", user, password) }
    }
}
