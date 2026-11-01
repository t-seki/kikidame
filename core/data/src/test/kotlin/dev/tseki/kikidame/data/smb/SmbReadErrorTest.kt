package dev.tseki.kikidame.data.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import org.junit.Test
import java.io.InterruptedIOException
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * SMB のファイルを読むストリームの、読み取り中の例外の変え方（[smbReadError]）。
 * [CancellationException] は [RuntimeException] の一種なので、[IOException] に変えずにそのまま投げることを押さえる。
 */
class SmbReadErrorTest {
    @Test
    fun smbApiExceptionBecomesIOException() {
        val e = SMBApiException(NtStatus.STATUS_ACCESS_DENIED.value, SMB2MessageCommandCode.SMB2_READ, null)
        val mapped = assertIs<IOException>(smbReadError(e))
        assertSame(e, mapped.cause)
        assertEquals("SMB read failed: ${e.status}", mapped.message)
    }

    @Test
    fun ioExceptionsPassThrough() {
        val io = IOException("io")
        assertSame(io, smbReadError(io))
        val interrupted = InterruptedIOException("interrupted")
        assertSame(interrupted, smbReadError(interrupted))
    }

    @Test
    fun cancellationPassesThrough() {
        val cancel = CancellationException("cancelled")
        assertSame(cancel, smbReadError(cancel))
    }

    @Test
    fun otherRuntimeExceptionsBecomeIOException() {
        val e = IllegalStateException("boom")
        val mapped = assertIs<IOException>(smbReadError(e))
        assertSame(e, mapped.cause)
        assertEquals("SMB read failed: boom", mapped.message)
    }

    @Test
    fun otherExceptionsPassThrough() {
        val e = Exception("checked")
        assertSame(e, smbReadError(e))
    }
}
