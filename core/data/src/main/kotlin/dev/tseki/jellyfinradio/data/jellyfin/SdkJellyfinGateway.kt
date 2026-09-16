package dev.tseki.jellyfinradio.data.jellyfin

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.tseki.jellyfinradio.domain.LibraryView
import dev.tseki.jellyfinradio.domain.ServerEpisode
import dev.tseki.jellyfinradio.domain.ServerException
import dev.tseki.jellyfinradio.domain.ServerItemId
import dev.tseki.jellyfinradio.domain.ServerProgram
import dev.tseki.jellyfinradio.domain.ServerSnapshot
import dev.tseki.jellyfinradio.domain.Session
import dev.tseki.jellyfinradio.domain.Ticks
import dev.tseki.jellyfinradio.domain.serverAiredAt
import kotlinx.datetime.LocalDate
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
                    fields = listOf(ItemFields.MEDIA_SOURCES, ItemFields.DATE_CREATED, ItemFields.PARENT_ID),
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

    private fun BaseItemDto.toServerEpisode(): ServerEpisode? {
        val albumId = albumId ?: return null
        val created = dateCreated ?: return null
        val source = mediaSources?.firstOrNull()
        return ServerEpisode(
            serverId = ServerItemId(id.toString()),
            programServerId = ServerItemId(albumId.toString()),
            title = name.orEmpty(),
            airedAt = serverAiredAt(premiereDate?.toKotlinLocalDate(), created.toKotlinLocalDate()),
            addedAt = created.toInstantUtc(),
            runtime = (runTimeTicks ?: source?.runTimeTicks)?.let(Ticks::toDuration) ?: Duration.ZERO,
            sizeBytes = source?.size ?: 0L,
            container = source?.container ?: container ?: "",
        )
    }

    /** SDK の例外を [ServerException] に正規化する。 */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: ServerException) {
        throw e
    } catch (e: InvalidStatusException) {
        if (e.status == 401 || e.status == 403) throw ServerException.Unauthorized(e)
        throw ServerException.Failed("HTTP ${e.status}", e)
    } catch (e: TimeoutException) {
        throw ServerException.Unreachable(e)
    } catch (e: SecureConnectionException) {
        throw ServerException.Unreachable(e)
    } catch (e: ApiClientException) {
        if (e.cause is IOException) throw ServerException.Unreachable(e)
        throw ServerException.Failed(e.message ?: "api error", e)
    } catch (e: IOException) {
        throw ServerException.Unreachable(e)
    }

    companion object {
        const val CLIENT_NAME = "Jellyfin Radio"
        const val PAGE_SIZE = 500
    }
}

/** サーバの日時は UTC のナイーブな `LocalDateTime` で来る。 */
private fun java.time.LocalDateTime.toKotlinLocalDate(): LocalDate =
    LocalDate(year, monthValue, dayOfMonth)

private fun java.time.LocalDateTime.toInstantUtc(): Instant =
    toInstant(java.time.ZoneOffset.UTC).toKotlinInstant()
