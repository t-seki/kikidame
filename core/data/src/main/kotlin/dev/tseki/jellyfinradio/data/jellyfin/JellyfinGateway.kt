package dev.tseki.jellyfinradio.data.jellyfin

import dev.tseki.jellyfinradio.domain.LibraryView
import dev.tseki.jellyfinradio.domain.ServerException
import dev.tseki.jellyfinradio.domain.ServerItemId
import dev.tseki.jellyfinradio.domain.ServerSnapshot
import dev.tseki.jellyfinradio.domain.Session

/** 認証済みの呼び出しに必要な最小限。 */
data class ServerCredentials(val serverUrl: String, val accessToken: String, val userId: String)

fun Session.credentials(): ServerCredentials = ServerCredentials(serverUrl, accessToken, userId)

/**
 * Jellyfin サーバとの境界。SDK への依存はこの実装にだけ閉じ込め、Repository のテストはフェイクで行う。
 * 失敗はすべて [ServerException] に正規化する。
 */
interface JellyfinGateway {
    suspend fun signIn(serverUrl: String, userName: String, password: String): Session

    suspend fun listLibraries(credentials: ServerCredentials): List<LibraryView>

    /** ライブラリ全体を取得する。番組に属さない各回は含めない。 */
    suspend fun fetchLibrary(credentials: ServerCredentials, libraryId: ServerItemId): ServerSnapshot
}
