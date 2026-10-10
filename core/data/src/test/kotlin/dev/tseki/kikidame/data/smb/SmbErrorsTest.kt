package dev.tseki.kikidame.data.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import dev.tseki.kikidame.domain.ServerException
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * smbj の失敗の分類（#198）。[dev.tseki.kikidame.data.sharedfolder.FolderTree] の約束
 * （「無い」だけを戻り値、接続・認証・権限の失敗は例外）に合わせる。ネットワークには出ない。
 */
class SmbErrorsTest {
    @Test
    fun onlyMissingPathsAreNotFound() {
        assertTrue(SmbErrors.isNotFound(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND))
        assertTrue(SmbErrors.isNotFound(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND))
        assertTrue(SmbErrors.isNotFound(NtStatus.STATUS_NOT_A_DIRECTORY))
        // 認証・権限・共有名の失敗は「無い」ではない。null で返すと全番組が消失と判断される（PR #202（#197）1 周目 #3）
        assertFalse(SmbErrors.isNotFound(NtStatus.STATUS_LOGON_FAILURE))
        assertFalse(SmbErrors.isNotFound(NtStatus.STATUS_ACCESS_DENIED))
        assertFalse(SmbErrors.isNotFound(NtStatus.STATUS_BAD_NETWORK_NAME))
        assertFalse(SmbErrors.isNotFound(NtStatus.STATUS_SUCCESS))
    }

    @Test
    fun badCredentialsAreUnauthorizedEverywhere() {
        for (status in listOf(NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_PASSWORD_EXPIRED, NtStatus.STATUS_ACCOUNT_DISABLED)) {
            assertIs<ServerException.Unauthorized>(SmbErrors.classify(status, connecting = true, path = ""))
            assertIs<ServerException.Unauthorized>(SmbErrors.classify(status, connecting = false, path = "a"))
        }
    }

    /** 共有につなぐ段階の「アクセス拒否」は共有の権利が無いこと。つないだ後の個別のパスでは、そのパスだけの問題。 */
    @Test
    fun accessDeniedDependsOnWhetherWeAreConnecting() {
        assertIs<ServerException.Unauthorized>(SmbErrors.classify(NtStatus.STATUS_ACCESS_DENIED, connecting = true, path = ""))
        val failed = assertIs<ServerException.Failed>(SmbErrors.classify(NtStatus.STATUS_ACCESS_DENIED, connecting = false, path = "A/B"))
        assertEquals("access denied: A/B", failed.message)
    }

    @Test
    fun missingShareIsFailedNotUnauthorized() {
        assertIs<ServerException.Failed>(SmbErrors.classify(NtStatus.STATUS_BAD_NETWORK_NAME, connecting = true, path = ""))
    }

    @Test
    fun missingFileIsFileNotFound() {
        assertIs<FileNotFoundException>(SmbErrors.classify(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, connecting = false, path = "A/B/a.m4a"))
    }

    @Test
    fun translateClassifiesSmbApiExceptionsAndLeavesTheRestAlone() {
        val logon = SMBApiException(NtStatus.STATUS_LOGON_FAILURE.value, SMB2MessageCommandCode.SMB2_SESSION_SETUP, null)
        assertIs<ServerException.Unauthorized>(SmbErrors.translate(logon, connecting = true))

        // 届かない（名前が引けない、タイムアウト）は IOException のまま。SharedFolderSource が Unreachable にする
        val io = IOException("unknown host")
        assertSame(io, SmbErrors.translate(io, connecting = true))
        val failed = ServerException.Failed("x")
        assertSame(failed, SmbErrors.translate(failed, connecting = false))
    }

    /** smbj の実行時の例外は IOException に包む。割り込みと取り消しは包まない（FolderTree の契約）。 */
    @Test
    fun otherRuntimeFailuresBecomeIoExceptionButInterruptionsPassThrough() {
        val runtime = IllegalStateException("bad packet")
        val wrapped = assertIs<IOException>(SmbErrors.translate(runtime, connecting = false, path = "a"))
        assertSame(runtime, wrapped.cause)

        val interrupted = java.io.InterruptedIOException("interrupted")
        assertSame(interrupted, SmbErrors.translate(interrupted, connecting = false))
        val cancelled = java.util.concurrent.CancellationException("cancelled")
        assertSame(cancelled, SmbErrors.translate(cancelled, connecting = false))
    }
}
