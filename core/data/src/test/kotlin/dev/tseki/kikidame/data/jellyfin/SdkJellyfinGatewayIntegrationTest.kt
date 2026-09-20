package dev.tseki.kikidame.data.jellyfin

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tseki.kikidame.domain.PublishedAt
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.ServerItemId
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * 実サーバに対する [SdkJellyfinGateway] の統合テスト（#97）。`scripts/jellyfin-testserver.sh up 1010|12` で立てた
 * 合成ライブラリ（番組 2 × 各回 3、`radio`）を前提に、[JellyfinGateway] の全メソッドを 1 回ずつ呼ぶ。
 *
 * 環境変数 `KIKIDAME_JELLYFIN_URL`（と `KIKIDAME_JELLYFIN_USER` / `KIKIDAME_JELLYFIN_PASSWORD`、既定は
 * スクリプトと同じ `kikidame` / `kikidame-test`）が無ければスキップするので、CI と普段の `./gradlew test` には影響しない:
 *
 *   KIKIDAME_JELLYFIN_URL=http://localhost:8097 ./gradlew :core:data:test \
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

    private suspend fun radio(credentials: ServerCredentials): ServerItemId {
        val libraries = gateway.listLibraries(credentials)
        val radio = libraries.firstOrNull { it.name == "radio" }
        assertNotNull(radio, "ライブラリ radio が無い: $libraries")
        assertTrue(radio.isMusic, "radio は音楽ライブラリのはず: $radio")
        assertEquals("music", radio.collectionType)
        return radio.id
    }

    @Test
    fun libraryHasTwoProgramsAndSixEpisodes() = runTest {
        val credentials = signIn()
        val snapshot = gateway.fetchLibrary(credentials, radio(credentials))

        assertEquals(setOf("テスト番組A", "テスト番組B"), snapshot.programs.map { it.name }.toSet())
        assertEquals("ニッポン放送", snapshot.programs.first { it.name == "テスト番組A" }.publisherName)
        assertEquals("TBSラジオ", snapshot.programs.first { it.name == "テスト番組B" }.publisherName)

        assertEquals(6, snapshot.episodes.size)
        val programIds = snapshot.programs.map { it.serverId }.toSet()
        assertTrue(snapshot.episodes.all { it.programServerId in programIds }, "番組に属さない各回が混ざっている")
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
        val snapshot = gateway.fetchLibrary(credentials, radio(credentials))
        val programA = snapshot.programs.first { it.name == "テスト番組A" }

        val episodes = gateway.fetchProgramEpisodes(credentials, programA.serverId)
        assertEquals(setOf("2026-09-14", "2026-09-14 (1)", "夏休みスペシャル"), episodes.map { it.title }.toSet())
        assertTrue(episodes.all { it.programServerId == programA.serverId })

        val program = gateway.fetchProgram(credentials, programA.serverId)
        assertNotNull(program)
        assertEquals("テスト番組A", program.name)
        assertEquals("ニッポン放送", program.publisherName)

        // サーバに無い番組は null（消失）。各回の ID を番組として引いても null
        assertNull(gateway.fetchProgram(credentials, ServerItemId(UUID.randomUUID().toString())))
        assertNull(gateway.fetchProgram(credentials, episodes.first().serverId))
    }

    @Test
    fun downloadStreamsTheOriginalAndHonoursRange() = runTest {
        val credentials = signIn()
        val snapshot = gateway.fetchLibrary(credentials, radio(credentials))
        val episode = snapshot.episodes.first { it.title == "2026-09-07" }

        val whole = gateway.openDownload(credentials, episode.serverId, rangeStart = 0)
        val total = whole.use { stream ->
            assertNull(stream.resumedFrom)
            val bytes = stream.body.readBytes()
            assertTrue(bytes.size > 1_000, "本文が短すぎる: ${bytes.size}")
            assertEquals(bytes.size.toLong(), stream.totalBytes, "Content-Length と本文の長さ")
            bytes.size.toLong()
        }

        gateway.openDownload(credentials, episode.serverId, rangeStart = 100).use { stream ->
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

    @Test
    fun badCredentialsAreUnauthorized() = runTest {
        assertFailsWith<ServerException.Unauthorized> { gateway.signIn(url!!, user, "wrong-$password") }

        val credentials = signIn()
        val bogus = credentials.copy(accessToken = "0123456789abcdef0123456789abcdef")
        assertFailsWith<ServerException.Unauthorized> { gateway.listLibraries(bogus) }
        val episode = gateway.fetchLibrary(credentials, radio(credentials)).episodes.first()
        assertFailsWith<ServerException.Unauthorized> { gateway.openDownload(bogus, episode.serverId, 0) }
    }

    @Test
    fun closedPortIsUnreachable() = runTest {
        assertFailsWith<ServerException.Unreachable> { gateway.signIn("http://127.0.0.1:1", user, password) }
    }
}
