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
import dev.tseki.kikidame.data.sharedfolder.FolderEntry
import dev.tseki.kikidame.data.sharedfolder.FolderFileStream
import dev.tseki.kikidame.data.sharedfolder.FolderTree
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.domain.SmbConnection
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import kotlin.time.Instant

/** 使い終わったら閉じる [FolderTree]。閉じると接続も閉じる。 */
interface CloseableFolderTree : FolderTree, AutoCloseable

/** 接続の設定から木を作る。1 回の取得元の操作ごとに作って閉じる（接続の寿命を呼び出しの外に持ち出さない）。 */
fun interface FolderTreeFactory {
    fun open(connection: SmbConnection): CloseableFolderTree
}

/**
 * SMB の共有を [FolderTree] として見せる（#198）。smbj の SMB2/3 を使う（SMB1 は非対応）。
 * 接続は最初に木を使うときに張り、[close] で閉じる。読むだけで、書き込み・削除はしない。
 *
 * - 接続・認証・権限の失敗は必ず例外にする。[list] が null を返すのは、そのパスが無いときだけ（[SmbErrors]）。
 *   null で返すと、共有が外れたときに `fetchProgram` が全番組を消失と判断する（PR #202（#197）1 周目 #3）
 * - ゲスト接続は smbj の [AuthenticationContext.guest]。NAS ごとに通るかは実機で確かめる（未確認）
 * - 単体テストしない（ネットワークに出るため）。パスの組み立てと失敗の分類は [SmbPaths] と [SmbErrors] で試す
 *
 * [read] は同じファイルを続けて読むことが多いので、最後に開いたファイルのハンドルを持ち回す。
 * 呼び出しはすべて blocking で、複数のスレッドから呼ばれても 1 つずつ処理する。
 */
class SmbFolderTree(private val connection: SmbConnection) : CloseableFolderTree {
    private var client: SMBClient? = null
    private var smbConnection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private var readHandle: Pair<String, File>? = null

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
        }
    }

    @Synchronized
    override fun read(path: String, position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val file = readHandle?.takeIf { it.first == path }?.second ?: openForRead(path).also { opened ->
            closeQuietly { readHandle?.second?.close() }
            readHandle = path to opened
        }
        return try {
            file.read(buffer, position, offset, size.coerceAtMost(MAX_CHUNK))
        } catch (e: SMBApiException) {
            throw SmbErrors.translate(e, connecting = false, path = path)
        }
    }

    @Synchronized
    override fun open(path: String, offset: Long): FolderFileStream {
        val file = openForRead(path)
        return try {
            FolderFileStream(file.length, SmbFileInputStream(file, offset))
        } catch (e: Exception) {
            closeQuietly { file.close() }
            throw if (e is SMBApiException) SmbErrors.translate(e, connecting = false, path = path) else e
        }
    }

    @Synchronized
    override fun close() {
        closeQuietly { readHandle?.second?.close() }
        readHandle = null
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
        } catch (e: SMBApiException) {
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

    private inline fun closeQuietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L

        /** 1 回の読み取りの上限。大きすぎる要求を 1 往復で出さない。 */
        const val MAX_CHUNK = 512 * 1024
    }
}

/** [File] を [offset] から読む [InputStream]。閉じるとファイルのハンドルも閉じる。 */
private class SmbFileInputStream(private val file: File, offset: Long) : InputStream() {
    private var position = offset

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = try {
            file.read(b, position, off, len.coerceAtMost(CHUNK))
        } catch (e: SMBApiException) {
            throw IOException("SMB read failed: ${e.status}", e)
        }
        if (n <= 0) return -1
        position += n
        return n
    }

    override fun close() {
        file.close()
    }

    private companion object {
        const val CHUNK = 512 * 1024
    }
}
