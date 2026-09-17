package dev.tseki.jellyfinradio.data.jellyfin

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.tseki.jellyfinradio.domain.LibraryView
import dev.tseki.jellyfinradio.domain.ServerEpisode
import dev.tseki.jellyfinradio.domain.ServerException
import dev.tseki.jellyfinradio.domain.ServerItemId
import dev.tseki.jellyfinradio.domain.ServerProgram
import dev.tseki.jellyfinradio.domain.ServerSnapshot
import dev.tseki.jellyfinradio.domain.Session
import dev.tseki.jellyfinradio.domain.AiredAt
import dev.tseki.jellyfinradio.domain.Ticks
import dev.tseki.jellyfinradio.domain.serverAiredAt
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * jellyfin-sdk-kotlin による [JellyfinGateway]。Jellyfin 12 の `Authorization: MediaBrowser …` 形式のみを使う。
 * `DeviceId` は SDK の Android 既定（`ANDROID_ID` 由来）、`Device` は端末のモデル名。
 */
@Singleton
class SdkJellyfinGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : JellyfinGateway {

    private val jellyfin: Jellyfin by lazy {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0"
        createJellyfin {
            this.context = this@SdkJellyfinGateway.context
            clientInfo = ClientInfo(name = CLIENT_NAME, version = version)
        }
    }

    private fun api(serverUrl: String, accessToken: String? = null): ApiClient =
        jellyfin.createApi(baseUrl = serverUrl, accessToken = accessToken)

    override suspend fun signIn(serverUrl: String, userName: String, password: String): Session = call {
        val api = api(serverUrl)
        val result by api.authenticationApi.authenticateUserByName(username = userName, password = password)
        val token = result.accessToken ?: throw ServerException.Failed("no access token in response")
        val user = result.user ?: throw ServerException.Failed("no user in response")
        Session(
            serverUrl = serverUrl,
            userName = user.name ?: userName,
            userId = user.id.toString(),
            accessToken = token,
        )
    }

    override suspend fun listLibraries(credentials: ServerCredentials): List<LibraryView> = call {
        val api = api(credentials.serverUrl, credentials.accessToken)
        val views by api.userViewApi.getUserViews()
        views.items.map { view ->
            LibraryView(
                id = ServerItemId(view.id.toString()),
                name = view.name ?: view.id.toString(),
                collectionType = view.collectionType?.serialName,
                isMusic = view.collectionType == CollectionType.MUSIC,
            )
        }
    }

    override suspend fun fetchLibrary(credentials: ServerCredentials, libraryId: ServerItemId): ServerSnapshot = call {
        val api = api(credentials.serverUrl, credentials.accessToken)
        val parent = UUID.fromString(libraryId.value)

        val albums by api.libraryApi.getItems(
            GetItemsRequest(
                parentId = parent,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            ),
        )
        val programs = albums.items.map { album ->
            ServerProgram(
                serverId = ServerItemId(album.id.toString()),
                name = album.name.orEmpty(),
                stationName = album.albumArtist ?: album.albumArtists?.firstOrNull()?.name,
            )
        }
        val programIds = programs.map { it.serverId }.toSet()

        val episodes = ArrayList<ServerEpisode>()
        var start = 0
        while (true) {
            val page by api.libraryApi.getItems(
                GetItemsRequest(
                    parentId = parent,
                    recursive = true,
                    includeItemTypes = listOf(BaseItemKind.AUDIO),
                    // MediaSources は要求しない: SDK 1.9（Jellyfin 12 向け）は MediaStream.IsOriginal を必須と
                    // 見なすが、10.11 はそれを返さずデコードに失敗する。サイズは M3 のダウンロード時に取る
                    fields = listOf(ItemFields.DATE_CREATED, ItemFields.PARENT_ID),
                    sortBy = listOf(ItemSortBy.DATE_CREATED),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    startIndex = start,
                    limit = PAGE_SIZE,
                ),
            )
            for (item in page.items) {
                val episode = item.toServerEpisode() ?: continue
                // 取得した番組のどれにも属さない各回は取り込まない
                if (episode.programServerId in programIds) episodes += episode
            }
            start += page.items.size
            if (page.items.isEmpty() || start >= page.totalRecordCount) break
        }
        ServerSnapshot(programs, episodes)
    }

    /**
     * SDK にはストリーミング取得の API が無い（`getDownload` は本文を `byte[]` に全部読む）ので、
     * URL とヘッダだけ SDK に作らせて転送は OkHttp で行う。エンドポイントと認証形式は手書きしない。
     */
    override suspend fun openDownload(
        credentials: ServerCredentials,
        episodeServerId: ServerItemId,
        rangeStart: Long,
    ): DownloadStream = call {
        val api = api(credentials.serverUrl, credentials.accessToken)
        val url = api.libraryApi.getDownloadUrl(UUID.fromString(episodeServerId.value))
        val authorization = AuthorizationHeaderBuilder.buildHeader(
            clientName = api.clientInfo.name,
            clientVersion = api.clientInfo.version,
            deviceId = api.deviceInfo.id,
            deviceName = api.deviceInfo.name,
            accessToken = credentials.accessToken,
        )
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .apply { if (rangeStart > 0) header("Range", "bytes=$rangeStart-") }
            .build()
        val response = withContext(Dispatchers.IO) { downloadClient.newCall(request).execute() }
        when (response.code) {
            200, 206 -> Unit
            401, 403 -> {
                response.close()
                throw ServerException.Unauthorized()
            }
            else -> {
                val code = response.code
                response.close()
                throw ServerException.Failed("HTTP $code")
            }
        }
        val body = response.body ?: run {
            response.close()
            throw ServerException.Failed("empty body")
        }
        val contentRange = response.header("Content-Range")
        val resumedFrom = if (response.code == 206) parseContentRangeStart(contentRange) ?: rangeStart else null
        val totalBytes = when {
            response.code == 206 -> parseContentRangeTotal(contentRange)
            body.contentLength() >= 0 -> body.contentLength()
            else -> null
        }
        DownloadStream(resumedFrom = resumedFrom, totalBytes = totalBytes, body = body.byteStream()) { response.close() }
    }

    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun BaseItemDto.toServerEpisode(): ServerEpisode? {
        val albumId = albumId ?: return null
        val created = dateCreated ?: return null
        return ServerEpisode(
            serverId = ServerItemId(id.toString()),
            programServerId = ServerItemId(albumId.toString()),
            title = name.orEmpty(),
            airedAt = serverAiredAt(premiereDate?.toKotlinLocalDate(), created.toKotlinLocalDate()),
            addedAt = created.toInstantUtc(),
            runtime = runTimeTicks?.let(Ticks::toDuration) ?: Duration.ZERO,
            // ファイルサイズは基本フィールドに無い（MediaSources を避けるため）。M3 のダウンロードで確定する
            sizeBytes = null,
            container = container ?: "",
        )
    }

    /** SDK の例外を [ServerException] に正規化する。原因の連鎖はログに残す（トークンは含まれない）。 */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: ServerException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "server call failed: ${e.causeChain()}")
        throw when (e) {
            is InvalidStatusException ->
                if (e.status == 401 || e.status == 403) ServerException.Unauthorized(e) else ServerException.Failed("HTTP ${e.status}", e)
            is TimeoutException, is SecureConnectionException, is IOException -> ServerException.Unreachable(e)
            is ApiClientException ->
                if (e.cause is IOException) ServerException.Unreachable(e) else ServerException.Failed(e.message ?: "api error", e)
            else -> ServerException.Failed(e.message ?: e::class.simpleName ?: "error", e)
        }
    }

    private fun Throwable.causeChain(): String =
        generateSequence(this) { it.cause }.joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" }

    companion object {
        const val CLIENT_NAME = "Jellyfin Radio"
        const val PAGE_SIZE = 500
        private const val TAG = "JellyfinGateway"
        private val CONTENT_RANGE_START = Regex("bytes\\s+(\\d+)-")
        private val CONTENT_RANGE_TOTAL = Regex("/(\\d+)\\s*$")

        /** `Content-Range: bytes 1000-49999/50000` → 1000 */
        internal fun parseContentRangeStart(header: String?): Long? =
            header?.let(CONTENT_RANGE_START::find)?.groupValues?.get(1)?.toLongOrNull()

        /** `Content-Range: bytes 1000-49999/50000` → 50000。`*` なら null */
        internal fun parseContentRangeTotal(header: String?): Long? =
            header?.let(CONTENT_RANGE_TOTAL::find)?.groupValues?.get(1)?.toLongOrNull()
    }
}

/**
 * サーバの日時は UTC のナイーブな `LocalDateTime` で来る。日付を切り出す前に JST へ直す
 * （`DateCreated` が JST 9:00 前だと UTC では前日になる。`PremiereDate` は UTC 0 時で来るので同じ日付のまま）。
 */
internal fun java.time.LocalDateTime.toKotlinLocalDate(): LocalDate =
    toInstantUtc().toLocalDateTime(AiredAt.ZONE).date

internal fun java.time.LocalDateTime.toInstantUtc(): Instant =
    toInstant(java.time.ZoneOffset.UTC).toKotlinInstant()
