package dev.tseki.kikidame.data.repository
import android.util.Log
import androidx.room.withTransaction
import dev.tseki.kikidame.data.db.KikidameDatabase
import dev.tseki.kikidame.data.files.EpisodesDirectory
import dev.tseki.kikidame.data.session.SessionStore
import dev.tseki.kikidame.domain.LocalDataReset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
/**
 * 「別のサーバに接続」。番組・各回（外部キーで手元のファイル情報と再生位置も）とセッションを消し、音声ファイルも消す（#50）。
 * 行を消したあとにファイルへ到達する手段は無い（行の無いファイルを DB に取り込み直さない。サーバが存在の正）ので、残す意味が無い。
 * 消せなかったファイルがあってもログに残して先へ進む（接続画面へ行けなくなる方が困る）。残ったファイルは
 * `docs/development.md`「手元のファイルと DB の突き合わせ」の手順で消す。
 */
@Singleton
class RoomLocalDataReset @Inject constructor(
    private val db: KikidameDatabase,
    private val store: SessionStore,
    private val directory: EpisodesDirectory,
) : LocalDataReset {
    private companion object {
        const val TAG = "LocalDataReset"
    }
    override suspend fun resetAll() {
        db.withTransaction { db.programDao().deleteAll() }
        // ファイル I/O はトランザクションの外で
        withContext(Dispatchers.IO) {
            val children = directory.root?.listFiles()
            if (children == null) Log.w(TAG, "episodes directory unavailable; files not deleted")
            children?.forEach { if (!it.deleteRecursively()) Log.w(TAG, "could not delete ${it.path}") }
        }
        store.clearAll()
    }
}
