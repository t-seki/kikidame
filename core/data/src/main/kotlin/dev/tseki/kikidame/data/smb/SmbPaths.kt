package dev.tseki.kikidame.data.smb

/**
 * SMB の共有の中のパスの組み立て（#198）。smbj の外の、単体でテストできる部分。
 *
 * 利用者が入力する「共有の中のパス」（[normalizeBase]）に、[dev.tseki.kikidame.data.sharedfolder.FolderTree] の相対パス
 * （区切りは `/`、根は空文字列）をつなぎ、smbj に渡す共有の根からのパス（区切りは `\`、共有の直下は空文字列）にする（[resolve]）。
 */
object SmbPaths {
    /**
     * 利用者が入力した共有の中のパスを整える。区切りを `/` に揃え、前後と連続の区切りを除く。
     * 空（または区切りだけ）なら共有の直下を表す空文字列。`.` と `..` の段は受け付けない（共有の外へ出られるため）。
     */
    fun normalizeBase(input: String): String {
        val segments = input.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() }
        require(segments.none { it == "." || it == ".." }) { "path must not contain . or .." }
        return segments.joinToString("/")
    }

    /**
     * [base]（[normalizeBase] 済み）と、木の相対パス [relative]（`/` 区切り、根は空）をつなぎ、smbj に渡すパスにする。
     * どちらも空なら共有の直下（空文字列）。
     */
    fun resolve(base: String, relative: String): String =
        (base.split('/') + relative.split('/')).filter { it.isNotEmpty() }.joinToString("\\")

    /**
     * 「ホスト」の入力を整える。前後の空白と、先頭の `smb://`・`\\`・末尾の区切りを除く。
     * 空、または区切り・空白を含むなら例外（呼び出し側が入力の誤りとして扱う）。
     */
    fun normalizeHost(input: String): String {
        val host = input.trim()
            .removePrefix("smb://").removePrefix("SMB://")
            .trimStart('\\', '/')
            .trimEnd('\\', '/')
        require(host.isNotEmpty() && host.none { it == '/' || it == '\\' || it.isWhitespace() }) { "host is invalid" }
        return host
    }
}
