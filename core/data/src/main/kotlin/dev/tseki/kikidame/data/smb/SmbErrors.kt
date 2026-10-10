package dev.tseki.kikidame.data.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import dev.tseki.kikidame.domain.ServerException
import java.io.FileNotFoundException

/**
 * smbj の失敗を、[dev.tseki.kikidame.data.sharedfolder.FolderTree] の約束（接続・認証・権限の失敗は必ず例外にし、
 * 「無い」だけを戻り値で表す）と [ServerException] に合わせる（#198）。
 *
 * - 認証の失敗は [ServerException.Unauthorized]（Jellyfin の 401/403 と同じく接続をやり直させる）
 * - 届かない（名前が引けない、タイムアウトなど）は smbj が [java.io.IOException] で投げるので、そのまま返す。
 *   [dev.tseki.kikidame.data.sharedfolder.SharedFolderSource] が [ServerException.Unreachable] にする（判断保留）
 */
internal object SmbErrors {
    /** 「そのパスが無い」を表す状態。[dev.tseki.kikidame.data.sharedfolder.FolderTree.list] はこのときだけ null を返す。 */
    private val NOT_FOUND = setOf(
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
        NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
        NtStatus.STATUS_NOT_FOUND,
        NtStatus.STATUS_NOT_A_DIRECTORY,
    )

    /** 資格情報そのものが受け付けられない状態。 */
    private val BAD_CREDENTIALS = setOf(
        NtStatus.STATUS_LOGON_FAILURE,
        NtStatus.STATUS_PASSWORD_EXPIRED,
        NtStatus.STATUS_ACCOUNT_DISABLED,
        NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
    )

    fun isNotFound(status: NtStatus): Boolean = status in NOT_FOUND

    /**
     * [e] を投げ直す例外にする。[connecting] は共有につなぐ段階（認証と共有への接続）で、そこでの「アクセス拒否」は
     * 資格情報に共有への権利が無いことなので [ServerException.Unauthorized]。つないだ後の個別のパスの「アクセス拒否」は
     * そのパスだけの問題なので [ServerException.Failed]（読めないフォルダがあるたびにログアウトさせない）。
     * smbj 以外の例外（[java.io.IOException] など）はそのまま返す。
     */
    fun translate(e: Exception, connecting: Boolean, path: String = ""): Exception =
        if (e is SMBApiException) classify(e.status, connecting, path, e) else e

    fun classify(status: NtStatus, connecting: Boolean, path: String, cause: Exception? = null): Exception = when {
        status in BAD_CREDENTIALS -> ServerException.Unauthorized(cause)
        status == NtStatus.STATUS_ACCESS_DENIED ->
            if (connecting) ServerException.Unauthorized(cause) else ServerException.Failed("access denied: $path", cause)
        status == NtStatus.STATUS_BAD_NETWORK_NAME -> ServerException.Failed("share not found", cause)
        status in NOT_FOUND -> FileNotFoundException(path)
        else -> ServerException.Failed("SMB error $status", cause)
    }
}
