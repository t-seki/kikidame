package dev.tseki.jellyfinradio.data.repository

import androidx.room.withTransaction
import dev.tseki.jellyfinradio.data.db.JellyfinRadioDatabase
import dev.tseki.jellyfinradio.data.session.SessionStore
import dev.tseki.jellyfinradio.domain.LocalDataReset
import javax.inject.Inject
import javax.inject.Singleton

/** 「別のサーバに接続」。番組・各回（外部キーで手元のファイル情報と再生位置も）とセッションを消す。ファイルは消さない。 */
@Singleton
class RoomLocalDataReset @Inject constructor(
    private val db: JellyfinRadioDatabase,
    private val store: SessionStore,
) : LocalDataReset {
    override suspend fun resetAll() {
        db.withTransaction { db.programDao().deleteAll() }
        store.clearAll()
    }
}
