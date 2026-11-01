package dev.tseki.kikidame.data.sharedfolder

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.time.Instant

/**
 * メモリ上の [FolderTree]（テスト用）。ファイルを相対パスで置くと、途中のフォルダは自動で在ることになる。
 * [emptyDir] で空のフォルダも置ける。[failing] を立てると、どの操作も [IOException] を投げる。
 */
class MemoryFolderTree {
    private val files = LinkedHashMap<String, Pair<ByteArray, Instant>>()
    private val dirs = HashSet<String>()
    var failing = false
    val reads = ArrayList<String>()

    val tree: FolderTree = object : FolderTree {
        override fun list(path: String): List<FolderEntry>? {
            check()
            if (path != "" && path !in allDirs()) return null
            val prefix = if (path == "") "" else "$path/"
            val children = LinkedHashMap<String, FolderEntry>()
            for ((p, v) in files) {
                if (!p.startsWith(prefix)) continue
                val rest = p.removePrefix(prefix)
                val name = rest.substringBefore('/')
                children[name] = if ('/' in rest) {
                    FolderEntry(name, isDirectory = true, sizeBytes = 0, modifiedAt = EPOCH)
                } else {
                    FolderEntry(name, isDirectory = false, sizeBytes = v.first.size.toLong(), modifiedAt = v.second)
                }
            }
            for (d in allDirs()) {
                if (!d.startsWith(prefix)) continue
                val name = d.removePrefix(prefix).substringBefore('/')
                children.putIfAbsent(name, FolderEntry(name, isDirectory = true, sizeBytes = 0, modifiedAt = EPOCH))
            }
            return children.values.toList()
        }

        override fun read(path: String, position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            check()
            reads += path
            val bytes = files[path]?.first ?: throw FileNotFoundException(path)
            if (position >= bytes.size) return -1
            val n = minOf(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }

        override fun open(path: String, offset: Long): FolderFileStream {
            check()
            val bytes = files[path]?.first ?: throw FileNotFoundException(path)
            return FolderFileStream(bytes.size.toLong(), ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt()))
        }
    }

    fun put(path: String, bytes: ByteArray = ByteArray(16), modifiedAt: Instant = EPOCH) {
        files[path] = bytes to modifiedAt
    }

    fun remove(path: String) {
        files.remove(path)
    }

    fun rename(from: String, to: String) {
        files[to] = files.remove(from)!!
    }

    fun emptyDir(path: String) {
        dirs += path
    }

    private fun check() {
        if (failing) throw IOException("unreachable")
    }

    /** ファイルの親と [emptyDir] で置いたフォルダ（とその親）。 */
    private fun allDirs(): Set<String> {
        val out = HashSet<String>()
        fun addParents(p: String) {
            var cur = p
            while ('/' in cur) {
                cur = cur.substringBeforeLast('/')
                out += cur
            }
        }
        files.keys.forEach(::addParents)
        dirs.forEach { out += it; addParents(it) }
        return out
    }

    companion object {
        val EPOCH: Instant = Instant.parse("2026-09-01T00:00:00Z")
    }
}
