package dev.tseki.jellyfinradio.data.repository
import androidx.room.withTransaction
import dev.tseki.jellyfinradio.data.db.JellyfinRadioDatabase
import dev.tseki.jellyfinradio.data.files.EpisodesDirectory
import dev.tseki.jellyfinradio.data.session.SessionStore
import dev.tseki.jellyfinradio.domain.LocalDataReset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
/**
 * 「別のサーバに接続」。番組・各回（外部キーで手元のファイル情報と再生位置も）とセッションを消し、音声ファイルも消す（#50）。
 * 行を消したあとにファイルへ到達する手段は無い（孤児を DB に取り込み直さない。サーバが存在の正）ので、残す意味が無い。
 */
@Singleton
class RoomLocalDataReset @Inject constructor(
    private val db: JellyfinRadioDatabase,
    private val store: SessionStore,
    private val directory: EpisodesDirectory,
) : LocalDataReset {
    override suspend fun resetAll() {
        db.withTransaction { db.programDao().deleteAll() }
        // ファイル I/O はトランザクションの外で。消せなかったファイルがあっても接続画面へは進む（次の掃除は #50 の手順）
        withContext(Dispatchers.IO) { directory.root?.listFiles()?.forEach { it.deleteRecursively() } }
        store.clearAll()
    }
}
