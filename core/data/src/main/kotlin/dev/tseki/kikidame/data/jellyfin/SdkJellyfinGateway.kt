package dev.tseki.kikidame.data.jellyfin

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.tseki.kikidame.data.source.DownloadStream
import dev.tseki.kikidame.domain.LibraryKind
import dev.tseki.kikidame.domain.LibraryView
import dev.tseki.kikidame.domain.SelectedLibrary
import dev.tseki.kikidame.domain.SharedPlayState
import dev.tseki.kikidame.domain.SourceEpisode
import dev.tseki.kikidame.domain.SourceException
import dev.tseki.kikidame.domain.SourceItemId
import dev.tseki.kikidame.domain.SourceImage
import dev.tseki.kikidame.domain.SourceProgram
import dev.tseki.kikidame.domain.SourceSnapshot
import dev.tseki.kikidame.domain.Session
import dev.tseki.kikidame.domain.PublishedAt
import dev.tseki.kikidame.domain.Ticks
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
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * jellyfin-sdk-kotlin による [JellyfinGateway]。音楽のライブラリとブックのライブラリを読む。
 * ブックのライブラリでは、各回の UserData（再生位置・再生済み・`LastPlayedDate`）を一覧と一緒に読み、1 件ずつ読み書きもする（再生の共有、ADR 0010）。
 * Jellyfin 12 の `Authorization: MediaBrowser …` 形式のみを使う。
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
        val token = result.accessToken ?: throw SourceException.Failed("no access token in response")
        val user = result.user ?: throw SourceException.Failed("no user in response")
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
                id = SourceItemId(view.id.toString()),
                name = view.name ?: view.id.toString(),
                collectionType = view.collectionType?.serialName,
                kind = when (view.collectionType) {
                    CollectionType.MUSIC -> LibraryKind.MUSIC
                    CollectionType.BOOKS -> LibraryKind.BOOKS
                    else -> null
                },
            )
        }
    }

    override suspend fun fetchLibrary(credentials: ServerCredentials, library: SelectedLibrary): SourceSnapshot = call {
        val api = api(credentials.serverUrl, credentials.accessToken)
        when (library.kind) {
            LibraryKind.MUSIC -> fetchMusicLibrary(api, UUID.fromString(library.id.value))
            LibraryKind.BOOKS -> fetchBooks(api, credentials, library).let { SourceSnapshot(it.programs, it.episodes) }
        }
    }

    private suspend fun fetchMusicLibrary(api: ApiClient, parent: UUID): SourceSnapshot {
        val albums by api.libraryApi.getItems(
            GetItemsRequest(
                parentId = parent,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            ),
        )
        val programs = albums.items.map { it.toSourceProgram() }
        val programIds = programs.map { it.sourceId }.toSet()
        // 取得した番組のどれにも属さない各回は取り込まない
        return SourceSnapshot(programs, fetchAudio(api, parent).filter { it.programSourceId in programIds })
    }

    /**
     * ブックのライブラリでは、`albums=` の絞り込みが AudioBook に効かない Jellyfin がある（Jellyfin 10.11.11 では全件が返った）ので、
     * ライブラリ全体を取ってタグでまとめた中から選ぶ（ADR 0010 の決定 2）。
     */
    override suspend fun fetchProgramEpisodes(
        credentials: ServerCredentials,
        library: SelectedLibrary,
        programSourceId: SourceItemId,
    ): List<SourceEpisode> = call {
        val api = api(credentials.serverUrl, credentials.accessToken)
        when (library.kind) {
            LibraryKind.MUSIC -> {
                // ブックのライブラリの番組 ID（UUID でない）を音楽のライブラリで引いたときは、その番組は無い
                val album = programSourceId.toUuidOrNull() ?: return@call emptyList()
                fetchAudio(api, album).filter { it.programSourceId == programSourceId }
            }
            LibraryKind.BOOKS -> fetchBooks(api, credentials, library).episodes.filter { it.programSourceId == programSourceId }
        }
    }

    /**
     * 音楽のライブラリでは `getItem` ではなく `getItems(ids=…)` で引く。`getItem` は項目の全フィールド（`MediaSources` 含む）を返すので、
     * 番組 ID が各回（Audio）を指していたとき、`MediaStream.IsOriginal` を返さない Jellyfin ではその欠落でデコードに失敗する。
     * `getItems` は [fetchAudio] と同じ基本フィールドだけを返し、無い ID・番組でない ID は空の一覧になる。
     * ブックのライブラリでは番組のアイテムが無いので、ライブラリ全体を取ってタグでまとめた中から選ぶ。
     */
    override suspend fun fetchProgram(credentials: ServerCredentials, library: SelectedLibrary, programSourceId: SourceItemId): SourceProgram? = call {
        val api = api(credentials.serverUrl, credentials.accessToken)
        when (library.kind) {
            LibraryKind.MUSIC -> {
                val album = programSourceId.toUuidOrNull() ?: return@call null
                val result by api.libraryApi.getItems(
                    GetItemsRequest(
                        ids = listOf(album),
                        includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                    ),
                )
                result.items.firstOrNull { it.type == BaseItemKind.MUSIC_ALBUM }?.toSourceProgram()
            }
            LibraryKind.BOOKS -> fetchBooks(api, credentials, library).programs.firstOrNull { it.sourceId == programSourceId }
        }
    }

    private fun BaseItemDto.toSourceProgram() = SourceProgram(
        sourceId = SourceItemId(id.toString()),
        name = name.orEmpty(),
        publisherName = publisher(),
        image = primaryImage(),
    )

    /** 配信元。AlbumArtist、無ければ AlbumArtists の 1 人目。 */
    private fun BaseItemDto.publisher(): String? = albumArtist ?: albumArtists?.firstOrNull()?.name

    /** 番組の画像。Primary の ImageTag が版。Primary が無ければ画像なし。 */
    private fun BaseItemDto.primaryImage(): SourceImage? = imageTags?.get(ImageType.PRIMARY)?.takeIf { it.isNotBlank() }?.let(::SourceImage)

    /** `parent` 配下の Audio を全部。ライブラリでも番組（MusicAlbum）でも同じ形。 */
    private suspend fun fetchAudio(api: ApiClient, parent: UUID): List<SourceEpisode> =
        fetchAll(
            api,
            GetItemsRequest(
                parentId = parent,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                // MediaSources は要求しない: SDK 1.9（Jellyfin 12 向け）は MediaStream.IsOriginal を必須と
                // 見なすが、それを返さない Jellyfin ではデコードに失敗する。サイズはダウンロードの完了時に実際の値で書き、
                // 差し替えの判断（ADR 0011）で要るときだけ [fetchFileSize] で問い合わせる。Etag はファイルの版（ADR 0011）
                fields = listOf(ItemFields.DATE_CREATED, ItemFields.PARENT_ID, ItemFields.ETAG),
                sortBy = listOf(ItemSortBy.DATE_CREATED),
                sortOrder = listOf(SortOrder.DESCENDING),
            ),
        ).mapNotNull { item -> item.albumId?.let { item.toSourceEpisode(SourceItemId(it.toString())) } }

    /** [request] の一覧を [PAGE_SIZE] 件ずつ全部。 */
    private suspend fun fetchAll(api: ApiClient, request: GetItemsRequest): List<BaseItemDto> {
        val items = ArrayList<BaseItemDto>()
        var start = 0
        while (true) {
            val page by api.libraryApi.getItems(request.copy(startIndex = start, limit = PAGE_SIZE))
            items += page.items
            start += page.items.size
            if (page.items.isEmpty() || start >= page.totalRecordCount) break
        }
        return items
    }

    /** ブックのライブラリを番組・各回にまとめたもの。[folders] は番組 ID → 番組のフォルダ（画像を持つ Folder）の id。 */
    private class BookListing(val programs: List<SourceProgram>, val episodes: List<SourceEpisode>, val folders: Map<SourceItemId, UUID>)

    /**
     * ブックのライブラリ全体を取り、各回（AudioBook）を Album と AlbumArtist のタグで番組にまとめる（ADR 0010 の決定 2）。
     * Album の無い各回は、どの番組にも属さないので取り込まない。番組の取得元 ID は `<AlbumArtist>/<Album>`（AlbumArtist が無ければ `/<Album>`）。
     *
     * 番組の画像は番組のフォルダ（Folder）の Primary。番組のフォルダとみなすのは、その番組の各回の親がどれも同じ Folder で、
     * そのフォルダの名前が Album と同じときだけ。各回が 1 本だけの番組は、各回の親が配信元のフォルダになる（Jellyfin で確かめた）ので、
     * 名前で外し、配信元の画像を番組の画像にしない。見分けられなければ画像なし（ADR 0009）。
     * 番組のフォルダは [bookFolders] に覚え、[fetchProgramImage] で使う。
     */
    private suspend fun fetchBooks(api: ApiClient, credentials: ServerCredentials, library: SelectedLibrary): BookListing {
        val parent = UUID.fromString(library.id.value)
        val folders = fetchAll(
            api,
            GetItemsRequest(parentId = parent, recursive = true, includeItemTypes = listOf(BaseItemKind.FOLDER), fields = listOf(ItemFields.PARENT_ID)),
        ).associateBy { it.id }
        val books = fetchAll(
            api,
            GetItemsRequest(
                parentId = parent,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.AUDIO_BOOK),
                // AudioBook は Container を返さない（Jellyfin 10.10.7 と 12.0 で確かめた）ので、拡張子を Path から取る
                // Etag はファイルの版（ADR 0011）
                fields = listOf(ItemFields.DATE_CREATED, ItemFields.PARENT_ID, ItemFields.PATH, ItemFields.ETAG),
                // 再生の共有（ADR 0010）: 各回の UserData を一覧と一緒に読む（回ごとに問い合わせない）
                userId = UUID.fromString(credentials.userId),
                enableUserData = true,
                sortBy = listOf(ItemSortBy.DATE_CREATED),
                sortOrder = listOf(SortOrder.DESCENDING),
            ),
        )
        val byProgram = LinkedHashMap<SourceItemId, MutableList<BaseItemDto>>()
        for (book in books) byProgram.getOrPut(book.bookProgramId() ?: continue) { ArrayList() } += book

        val programFolders = HashMap<SourceItemId, UUID>()
        val programs = byProgram.map { (programId, items) ->
            val first = items.first()
            val album = first.album.orEmpty()
            val folder = items.map { it.parentId }.distinct().singleOrNull()?.let(folders::get)?.takeIf { it.name == album }
            if (folder != null) programFolders[programId] = folder.id
            SourceProgram(sourceId = programId, name = album, publisherName = first.publisher(), image = folder?.primaryImage())
        }
        val episodes = books.mapNotNull { book ->
            book.bookProgramId()?.let { book.toSourceEpisode(it)?.copy(shared = book.userData?.toSharedPlayState()) }
        }
        bookFolders[BookLibraryKey(credentials.serverUrl, credentials.userId, library.id)] = programFolders
        return BookListing(programs, episodes, programFolders)
    }

    /** ブックのライブラリの番組 ID（`<AlbumArtist>/<Album>`）。Album が無ければ null。分けて読み戻すことはしない（AlbumArtist に `/` が入りうる）。 */
    private fun BaseItemDto.bookProgramId(): SourceItemId? {
        val album = album?.takeIf { it.isNotBlank() } ?: return null
        return SourceItemId("${publisher().orEmpty()}/$album")
    }

    private data class BookLibraryKey(val serverUrl: String, val userId: String, val libraryId: SourceItemId)

    /**
     * ブックのライブラリごとの、最後に取った一覧の番組 ID → 番組のフォルダの id。画像の取得（[fetchProgramImage]）は、同期の一覧の直後に
     * 番組ごとに呼ばれるので、番組ごとにライブラリ全体を取り直さないために覚える。覚えていない番組だけ一覧を取り直す。
     */
    private val bookFolders = ConcurrentHashMap<BookLibraryKey, Map<SourceItemId, UUID>>()

    /**
     * SDK にはストリーミング取得の API が無い（`getDownload` は本文を `byte[]` に全部読む）ので、
     * URL とヘッダだけ SDK に作らせて転送は OkHttp で行う。エンドポイントと認証形式は手書きしない。
     */
    override suspend fun openDownload(
        credentials: ServerCredentials,
        episodeSourceId: SourceItemId,
        rangeStart: Long,
    ): DownloadStream = call {
        val api = api(credentials.serverUrl, credentials.accessToken)
        val url = api.libraryApi.getDownloadUrl(UUID.fromString(episodeSourceId.value))
        val authorization = authorizationHeader(api, credentials)
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
                throw SourceException.Unauthorized()
            }
            else -> {
                val code = response.code
                response.close()
                throw SourceException.Failed("HTTP $code")
            }
        }
        val body = response.body ?: run {
            response.close()
            throw SourceException.Failed("empty body")
        }
        val contentRange = response.header("Content-Range")
        Log.i(TAG, "download ${episodeSourceId.value} from $rangeStart -> HTTP ${response.code} Content-Range=$contentRange")
        val resumedFrom = if (response.code == 206) parseContentRangeStart(contentRange) ?: rangeStart else null
        val totalBytes = when {
            // 206 で Content-Range が無い／読めないときは、残りの長さから全体を求めて完全性チェックを生かす
            response.code == 206 -> parseContentRangeTotal(contentRange)
                ?: body.contentLength().takeIf { it >= 0 }?.let { it + (resumedFrom ?: 0L) }
            body.contentLength() >= 0 -> body.contentLength()
            else -> null
        }
        DownloadStream(resumedFrom = resumedFrom, totalBytes = totalBytes, body = body.byteStream()) { response.close() }
    }

    /**
     * 原本の全長を `Range: bytes=0-0` で取る（ADR 0011）。HEAD は Jellyfin 10.10.7・12.0 とも 405 を返すので使わない。
     * 206 なら `Content-Range` の全長、Range を無視して 200 で全体が返ったら `Content-Length`（本文は読まずに閉じる）。
     * 書き換えた直後、Jellyfin が走査し直す前でも、ディスク上の今の大きさが返った（Jellyfin 10.10.7・12.0 で確かめた）。
     */
    override suspend fun fetchFileSize(credentials: ServerCredentials, episodeSourceId: SourceItemId): Long? = call {
        val api = api(credentials.serverUrl, credentials.accessToken)
        val url = api.libraryApi.getDownloadUrl(UUID.fromString(episodeSourceId.value))
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorizationHeader(api, credentials))
            .header("Range", "bytes=0-0")
            .build()
        withContext(Dispatchers.IO) {
            downloadClient.newCall(request).execute().use { response ->
                when (response.code) {
                    206 -> parseContentRangeTotal(response.header("Content-Range"))
                    200 -> response.body?.contentLength()?.takeIf { it >= 0 }
                    401, 403 -> throw SourceException.Unauthorized()
                    else -> throw SourceException.Failed("HTTP ${response.code}")
                }
            }
        }
    }

    /**
     * 番組の Primary の画像を全部読む。音楽のライブラリは MusicAlbum、ブックのライブラリは番組のフォルダ（[fetchBooks]）の画像。
     * URL と認証ヘッダは SDK に作らせ、転送は OkHttp で行う（[openDownload] と同じ）。
     * 画像が無い（404）・番組のフォルダが無い・[MAX_PROGRAM_IMAGE_BYTES] を超えるときは null。
     */
    override suspend fun fetchProgramImage(credentials: ServerCredentials, library: SelectedLibrary, programSourceId: SourceItemId): ByteArray? = call {
        val api = api(credentials.serverUrl, credentials.accessToken)
        val item = when (library.kind) {
            LibraryKind.MUSIC -> programSourceId.toUuidOrNull()
            LibraryKind.BOOKS -> bookFolders[BookLibraryKey(credentials.serverUrl, credentials.userId, library.id)]?.get(programSourceId)
                ?: fetchBooks(api, credentials, library).folders[programSourceId]
        } ?: return@call null
        val url = api.imageApi.getItemImageUrl(item, ImageType.PRIMARY)
        val request = Request.Builder().url(url).header("Authorization", authorizationHeader(api, credentials)).build()
        withContext(Dispatchers.IO) {
            downloadClient.newCall(request).execute().use { response ->
                if (!imageStatusHasBody(response.code)) return@use null
                val body = response.body ?: throw SourceException.Failed("empty body")
                readLimited(body.byteStream(), body.contentLength(), MAX_PROGRAM_IMAGE_BYTES)
            }
        }
    }

    override suspend fun fetchPlayState(credentials: ServerCredentials, episodeSourceId: SourceItemId, timeout: Duration?): SharedPlayState = call {
        val api = api(credentials.serverUrl, credentials.accessToken)
        val read: suspend () -> SharedPlayState = {
            val data by api.userDataApi.getItemUserData(UUID.fromString(episodeSourceId.value), UUID.fromString(credentials.userId))
            data.toSharedPlayState()
        }
        // withTimeout の取り消しは CancellationException なので call が包まずに通してしまう。ここで到達不能に直す。
        // 時間は IO のディスパッチャで計る（呼び出し側の仮想の時計（テストの runTest）で計ると、通信を待たずに切れる）
        if (timeout == null) {
            read()
        } else {
            withContext(Dispatchers.IO) { withTimeoutOrNull(timeout) { read() } } ?: throw SourceException.Unreachable()
        }
    }

    /**
     * `LastPlayedDate` はタイムゾーンを持たない `LocalDateTime` で送る。サーバの日時は UTC のナイーブな値なので（[toInstantUtc]）、UTC で作る。
     * 送るのは位置・再生済み・`LastPlayedDate` だけ（ほかの項目は null。null の項目をサーバが変えずに残すかは、サーバのコードを読んでおらず未確認）。
     */
    override suspend fun writePlayState(credentials: ServerCredentials, episodeSourceId: SourceItemId, state: SharedPlayState): SharedPlayState = call {
        val api = api(credentials.serverUrl, credentials.accessToken)
        val written by api.userDataApi.updateItemUserData(
            UUID.fromString(episodeSourceId.value),
            UUID.fromString(credentials.userId),
            UpdateUserItemDataDto(
                playbackPositionTicks = Ticks.fromDuration(state.position),
                played = state.played,
                lastPlayedDate = state.lastPlayedAt?.let { java.time.LocalDateTime.ofInstant(it.toJavaInstant(), java.time.ZoneOffset.UTC) },
            ),
        )
        written.toSharedPlayState()
    }

    /** `LastPlayedDate` はミリ秒にそろえる（手元の「最後に見た値」はミリ秒で持つので、サーバの端数で「変わった」と誤らないように）。 */
    private fun UserItemDataDto.toSharedPlayState() = SharedPlayState(
        position = Ticks.toDuration(playbackPositionTicks),
        played = played,
        lastPlayedAt = lastPlayedDate?.toInstantUtc(),
    ).toMillis()

    private fun authorizationHeader(api: ApiClient, credentials: ServerCredentials): String =
        AuthorizationHeaderBuilder.buildHeader(
            clientName = api.clientInfo.name,
            clientVersion = api.clientInfo.version,
            deviceId = api.deviceInfo.id,
            deviceName = api.deviceInfo.name,
            accessToken = credentials.accessToken,
        )

    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 各回（音楽は Audio、ブックは AudioBook）。取り込まれた日時が無ければ null。 */
    private fun BaseItemDto.toSourceEpisode(programSourceId: SourceItemId): SourceEpisode? {
        val created = dateCreated ?: return null
        return SourceEpisode(
            sourceId = SourceItemId(id.toString()),
            programSourceId = programSourceId,
            title = name.orEmpty(),
            publishedAt = PublishedAt.of(premiereDate?.toKotlinLocalDate(), created.toKotlinLocalDate()),
            addedAt = created.toInstantUtc(),
            runtime = runTimeTicks?.let(Ticks::toDuration) ?: Duration.ZERO,
            // ファイルサイズは基本フィールドに無い（MediaSources を避けるため）。ダウンロードの完了時に確定する。
            // 差し替えのために問い合わせた大きさ（[fetchFileSize]）もここには載せない（手元の回の大きさを上書きしないため）
            sizeBytes = null,
            // AudioBook は Container を返さないので、Path（ブックのライブラリでだけ要求する）の拡張子で代える
            container = container ?: path?.substringAfterLast('/')?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase() ?: "",
            // 出演者。`Artists` は基本フィールドで返る（`ItemFields` に無く、要求の有無に依らない）。名前だけの一覧が無い・空なら `ArtistItems` から拾う
            performers = (artists?.takeIf { it.isNotEmpty() } ?: artistItems?.mapNotNull { it.name }).orEmpty().filter { it.isNotBlank() },
            // ファイルの版（ADR 0011）。`ItemFields.ETAG` を求めたときだけ返る。中身は DateLastSaved の MD5 で、メタデータの保存だけでも変わる
            version = etag?.takeIf { it.isNotBlank() },
        )
    }

    /** SDK の例外を [SourceException] に正規化する（コルーチンの取り消しは包まずに通す。SDK が取り消しをそのまま投げるかは未確認）。原因の連鎖はログに残す（トークンは含まれない）。 */
    internal suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: SourceException) {
        throw e
    } catch (e: CancellationException) {
        // コルーチンの取り消しは失敗ではない。Failed に包むと、取り消された同期が失敗の文言になり、取り消しが伝わらない
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "server call failed: ${e.causeChain()}")
        throw when (e) {
            is InvalidStatusException ->
                if (e.status == 401 || e.status == 403) SourceException.Unauthorized(e) else SourceException.Failed("HTTP ${e.status}", e)
            is TimeoutException, is SecureConnectionException, is IOException -> SourceException.Unreachable(e)
            is ApiClientException ->
                if (e.cause is IOException) SourceException.Unreachable(e) else SourceException.Failed(e.message ?: "api error", e)
            else -> SourceException.Failed(e.message ?: e::class.simpleName ?: "error", e)
        }
    }

    /** 音楽のライブラリの番組 ID（アイテムの id）として読む。ブックのライブラリの番組 ID など、UUID でなければ null。 */
    private fun SourceItemId.toUuidOrNull(): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    private fun Throwable.causeChain(): String =
        generateSequence(this) { it.cause }.joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" }

    companion object {
        const val CLIENT_NAME = "Kikidame"
        const val PAGE_SIZE = 500

        /** 画像の API の応答コード → 本文を読むか。200 は true、404（Primary が無い）は false、401/403 は [SourceException.Unauthorized]、ほかは [SourceException.Failed]。 */
        internal fun imageStatusHasBody(code: Int): Boolean = when (code) {
            200 -> true
            404 -> false
            401, 403 -> throw SourceException.Unauthorized()
            else -> throw SourceException.Failed("HTTP $code")
        }

        /**
         * 本文を [max] バイトまで読む。[contentLength]（不明なら負）が [max] を超える、または読みながら [max] を超えたら null
         * （切れたバイト列は返さない）。
         */
        internal fun readLimited(input: java.io.InputStream, contentLength: Long, max: Long): ByteArray? {
            if (contentLength > max) return null
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            input.use {
                while (true) {
                    val n = it.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > max) return null
                }
            }
            return out.toByteArray()
        }

        /** 番組の画像の大きさの上限。共有フォルダの `SharedFolderSource.MAX_PROGRAM_IMAGE_BYTES` と同じ値（10 MiB。根拠は無く、測っていない）。超えたら画像なし。 */
        const val MAX_PROGRAM_IMAGE_BYTES = 10L * 1024 * 1024
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
    toInstantUtc().toLocalDateTime(PublishedAt.ZONE).date

internal fun java.time.LocalDateTime.toInstantUtc(): Instant =
    toInstant(java.time.ZoneOffset.UTC).toKotlinInstant()
