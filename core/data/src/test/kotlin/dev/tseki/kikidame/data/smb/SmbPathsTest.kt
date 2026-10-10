package dev.tseki.kikidame.data.smb

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** SMB の共有の中のパスの組み立て（#198）。smbj を使わない部分。 */
class SmbPathsTest {
    @Test
    fun baseIsNormalisedToSlashSeparatedWithoutEdges() {
        assertEquals("", SmbPaths.normalizeBase(""))
        assertEquals("", SmbPaths.normalizeBase(" / \\ "))
        assertEquals("radio", SmbPaths.normalizeBase("/radio/"))
        assertEquals("radio/2026", SmbPaths.normalizeBase("\\\\radio//2026\\"))
        assertEquals("録音/ラジオ", SmbPaths.normalizeBase(" 録音 / ラジオ "))
    }

    @Test
    fun baseCannotLeaveTheShare() {
        assertFailsWith<IllegalArgumentException> { SmbPaths.normalizeBase("../other") }
        assertFailsWith<IllegalArgumentException> { SmbPaths.normalizeBase("radio/./x") }
    }

    @Test
    fun resolveJoinsBaseAndRelativePathWithBackslashes() {
        assertEquals("", SmbPaths.resolve("", ""), "both empty is the top of the share")
        assertEquals("radio", SmbPaths.resolve("radio", ""), "the root of the tree is the base folder")
        assertEquals("Publisher", SmbPaths.resolve("", "Publisher"))
        assertEquals("radio\\Publisher\\Program\\a.m4a", SmbPaths.resolve("radio", "Publisher/Program/a.m4a"))
        assertEquals("a\\b\\c", SmbPaths.resolve("a/b", "c"))
    }

    @Test
    fun hostLosesSchemeAndSeparators() {
        assertEquals("nas.local", SmbPaths.normalizeHost("nas.local"))
        assertEquals("192.168.1.10", SmbPaths.normalizeHost(" 192.168.1.10 "))
        assertEquals("nas.local", SmbPaths.normalizeHost("smb://nas.local/"))
        assertEquals("nas", SmbPaths.normalizeHost("\\\\nas"))
    }

    @Test
    fun hostMustBeASingleName() {
        assertFailsWith<IllegalArgumentException> { SmbPaths.normalizeHost("") }
        assertFailsWith<IllegalArgumentException> { SmbPaths.normalizeHost("nas/share") }
        assertFailsWith<IllegalArgumentException> { SmbPaths.normalizeHost("my nas") }
    }
}
