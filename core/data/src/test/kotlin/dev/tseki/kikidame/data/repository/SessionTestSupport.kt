package dev.tseki.kikidame.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.tseki.kikidame.data.session.SessionStore
import dev.tseki.kikidame.data.session.TokenCipher
import dev.tseki.kikidame.data.sharedfolder.FolderEntry
import dev.tseki.kikidame.data.sharedfolder.FolderFileStream
import dev.tseki.kikidame.data.smb.CloseableFolderTree
import dev.tseki.kikidame.data.smb.FolderTreeFactory
import dev.tseki.kikidame.domain.SmbConnection
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** 暗号化の代わりに印を付けるだけ。復号できたかはテストで見える。 */
class FakeTokenCipher : TokenCipher {
    override fun encrypt(plain: String): String = "enc[$plain]"
    override fun decrypt(stored: String): String = stored.removePrefix("enc[").removeSuffix("]")
}

fun testDataStore(dir: File, scope: CoroutineScope): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = scope) { File(dir, "session.preferences_pb") }

fun testSessionStore(dir: File, scope: CoroutineScope): SessionStore =
    SessionStore(testDataStore(dir, scope), FakeTokenCipher())

/** 共有フォルダの木の代わり。[entries] は相対パス（根は空）からその直下の子へ。[failure] があれば [list] が投げる。 */
class FakeFolderTree(
    var entries: Map<String, List<FolderEntry>> = mapOf("" to emptyList()),
    var failure: Exception? = null,
) : CloseableFolderTree {
    var closed = false
        private set

    override fun list(path: String): List<FolderEntry>? {
        failure?.let { throw it }
        return entries[path]
    }

    override fun read(path: String, position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        throw UnsupportedOperationException()

    override fun open(path: String, offset: Long): FolderFileStream = throw UnsupportedOperationException()

    override fun close() {
        closed = true
    }
}

class FakeFolderTreeFactory(val tree: FakeFolderTree = FakeFolderTree()) : FolderTreeFactory {
    val opened = mutableListOf<SmbConnection>()

    override fun open(connection: SmbConnection): CloseableFolderTree {
        opened += connection
        return tree
    }
}
