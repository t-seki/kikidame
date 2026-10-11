package dev.tseki.kikidame.data.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import dev.tseki.kikidame.data.sharedfolder.CloseableFolderTree
import dev.tseki.kikidame.data.sharedfolder.FolderEntry
import dev.tseki.kikidame.data.sharedfolder.FolderFileStream
import dev.tseki.kikidame.data.sharedfolder.FolderTree
import dev.tseki.kikidame.data.sharedfolder.LastOpened
import dev.tseki.kikidame.data.sharedfolder.PositionalInputStream
import dev.tseki.kikidame.data.sharedfolder.closeQuietly
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.SmbConnection
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.time.Instant

/**
 * SMB の共有を [FolderTree] として見せる（#198）。smbj の SMB2/3 を使う（SMB1 は非対応）。
 * 接続は最初に木を使うときに張り、[close] で閉じる。読むだけで、書き込み・削除はしない。
 *
 * - 接続・認証・権限の失敗は必ず例外にする。[list] が null を返すのは、そのパスが無いときだけ（[SmbErrors]）。
 *   null で返すと、共有が外れたときに `fetchProgram` が全番組を消失と判断する（PR #202（#197）1 周目 #3）
 * - ゲスト接続は smbj の [AuthenticationContext.guest]。NAS ごとに通るかは実機で確かめる（未確認）
 * - 全走査が失敗した操作をやり直す前に [reset] を呼ぶので、そこで接続を閉じ、次の操作で張り直す（#209）。
 *   切れた接続を smbj が「つながっている」と見せたまま残すかは確かめていない（未確認）ので、[share] の `isConnected` だけに頼らない
 * - 単体テストしない（ネットワークに出るため）。パスの組み立てと失敗の分類は [SmbPaths] と [SmbErrors] で試す
 *
 * [read] は同じファイルを続けて読むことが多いので、最後に開いたファイルのハンドルを持ち回す（[LastOpened]）。
 * 呼び出しはすべて blocking で、複数のスレッドから呼ばれても 1 つずつ処理する。
 */
class SmbFolderTree(private val connection: SmbConnection) : CloseableFolderTree {
    private var client: SMBClient? = null
    private var smbConnection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private val lastOpened = LastOpened<File>()

    @Synchronized
    override fun list(path: String): List<FolderEntry>? {
        val smbPath = SmbPaths.resolve(connection.path, path)
        return try {
            share().list(smbPath)
                .filter { it.fileName != "." && it.fileName != ".." }
                .map { info ->
                    FolderEntry(
                        name = info.fileName,
                        isDirectory = EnumWithValue.EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                        sizeBytes = info.endOfFile,
                        modifiedAt = Instant.fromEpochMilliseconds(info.lastWriteTime.toEpochMillis()),
                    )
                }
        } catch (e: SMBApiException) {
            if (SmbErrors.isNotFound(e.status)) null else throw SmbErrors.translate(e, connecting = false, path = path)
        } catch (e: Exception) {
            throw SmbErrors.translate(e, connecting = false, path = path)
        }
    }

    @Synchronized
    override fun read(path: String, position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val file = lastOpened.get(path, ::openForRead)
        return try {
            file.read(buffer, position, offset, size.coerceAtMost(MAX_CHUNK))
        } catch (e: Exception) {
            throw SmbErrors.translate(e, connecting = false, path = path)
        }
    }

    @Synchronized
    override fun open(path: String, offset: Long): FolderFileStream {
        val file = openForRead(path)
        return try {
            FolderFileStream(file.length, fileInputStream(file, offset))
        } catch (e: Exception) {
            closeQuietly { file.close() }
            throw SmbErrors.translate(e, connecting = false, path = path)
        }
    }

    /** 接続を閉じる（#209）。次の操作が [share] で張り直す。[close] と同じで、木はその後も使える。 */
    @Synchronized
    override fun reset() {
        close()
    }

    @Synchronized
    override fun close() {
        lastOpened.close()
        closeQuietly { share?.close() }
        share = null
        closeQuietly { session?.close() }
        session = null
        closeQuietly { smbConnection?.close() }
        smbConnection = null
        closeQuietly { client?.close() }
        client = null
    }

    private fun openForRead(path: String): File {
        val smbPath = SmbPaths.resolve(connection.path, path)
        return try {
            share().openFile(
                smbPath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        } catch (e: Exception) {
            throw SmbErrors.translate(e, connecting = false, path = path)
        }
    }

    /** 共有につなぐ（つながっていれば使い回す）。失敗したら張りかけた接続を閉じて例外にする。 */
    private fun share(): DiskShare {
        share?.takeIf { it.isConnected }?.let { return it }
        close()
        try {
            val newClient = SMBClient(
                SmbConfig.builder()
                    .withTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .withSoTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
            client = newClient
            val newConnection = newClient.connect(connection.host)
            smbConnection = newConnection
            val newSession = newConnection.authenticate(authenticationContext())
            session = newSession
            val newShare = newSession.connectShare(connection.share) as? DiskShare
                ?: throw ServerException.Failed("not a disk share: ${connection.share}")
            share = newShare
            return newShare
        } catch (e: Exception) {
            close()
            throw SmbErrors.translate(e, connecting = true)
        }
    }

    private fun authenticationContext(): AuthenticationContext {
        if (connection.guest) return AuthenticationContext.guest()
        // `DOMAIN\user` の形ならドメインを分ける。無ければ空（NAS のローカルのアカウント）
        val name = connection.userName
        val domain = name.substringBefore('\\', missingDelimiterValue = "")
        val user = name.substringAfter('\\')
        return AuthenticationContext(user, connection.password.toCharArray(), domain)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L

        /** 1 回の読み取りの上限。大きすぎる要求を 1 往復で出さない。 */
        const val MAX_CHUNK = 512 * 1024
    }
}

/**
 * [File] を [offset] から読む [java.io.InputStream]。閉じるとファイルのハンドルも閉じる。
 * 1 回に読む長さは [SMB_READ_CHUNK] までに抑える。読み取り中の例外は [smbReadError] で変える。
 */
private fun fileInputStream(file: File, offset: Long) = PositionalInputStream(
    offset = offset,
    readAt = { position, buffer, off, len -> file.read(buffer, position, off, len.coerceAtMost(SMB_READ_CHUNK)) },
    closeSource = { file.close() },
    mapError = ::smbReadError,
)

/** ストリームで 1 回に読む長さの上限。 */
private const val SMB_READ_CHUNK = 512 * 1024

/**
 * SMB のファイルを読むストリームの、読み取り中の例外を変える（#233 で `SmbFileInputStream` の catch の並びから移した。順も同じ）。
 *
 * - [SMBApiException] は [IOException] に変える
 * - [IOException]（`InterruptedIOException` を含む）と [CancellationException] は、そのまま投げる。
 *   [CancellationException] は [RuntimeException] の一種なので、下の変換より先に見る
 * - そのほかの [RuntimeException] は [IOException] に変える
 * - それ以外はそのまま投げる
 */
internal fun smbReadError(e: Exception): Exception = when (e) {
    is SMBApiException -> IOException("SMB read failed: ${e.status}", e)
    is IOException -> e
    is CancellationException -> e
    is RuntimeException -> IOException("SMB read failed: ${e.message}", e)
    else -> e
}
