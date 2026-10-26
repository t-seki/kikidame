package dev.tseki.kikidame.domain

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * 取得元（CONTEXT.md）に対する現在の接続状態。取得元は同時に 1 つ（epic #195 の決定 2）。
 * Jellyfin は 1 サーバ・1 ユーザー・1 ライブラリ、共有フォルダ（SMB）は 1 つの共有、端末のフォルダ（SAF、#199）は 1 つのフォルダ。
 */
sealed interface SessionState {
    /**
     * 一度も接続していない、またはログアウト済み。手元のデータは残っている。
     * 直前に使っていた取得元の入力補助として、Jellyfin なら [lastServerUrl]・[lastUserName]、SMB なら [lastSmb]（パスワードは空）、
     * 端末のフォルダなら [lastDeviceFolder]（アクセスが切れたフォルダ。#199）を持つ。どれも無ければ初回（取得元の選択画面から始める）。
     */
    data class SignedOut(
        val lastServerUrl: String?,
        val lastUserName: String?,
        val lastSmb: SmbConnection? = null,
        val lastDeviceFolder: DeviceFolder? = null,
    ) : SessionState

    /** Jellyfin にログイン済みだがライブラリ未選択。 */
    data class NeedsLibrary(val session: Session) : SessionState

    /**
     * 取得元に接続済みで、同期できる。[source] が取得元の種類ごとの接続の情報。
     * [lastFetchedAt] は最後に全走査が成功した時刻、[lastAttemptedAt] は最後に全走査を試みた時刻（成功・失敗を問わない。#135）。
     * 起動時同期は両方の新しい方から間をあける。
     * [lastSyncAttempt] は全走査の最後の試みの結果と時刻（「Wi-Fi ではない」の見送りを含む。#346）。[lastAttemptedAt] とは別に持つ
     * （[lastAttemptedAt] は見送りでは更新しない）。[syncFailureNotified] は同期の失敗の通知を出したか（成功するまで出し直さない。#346）。
     */
    data class Ready(
        val source: ConnectedSource,
        val lastFetchedAt: Instant?,
        val lastAttemptedAt: Instant? = null,
        val lastSyncAttempt: SyncAttempt? = null,
        val syncFailureNotified: Boolean = false,
    ) : SessionState
}

/**
 * 全走査の最後の試み（#346）。手動（引っ張って更新・初回の取得・手動が合流した裏の同期）も裏の同期も記録し、1 番組の同期は記録しない。
 * 401（ログアウトする）と取り消しは記録しない。
 */
data class SyncAttempt(val at: Instant, val outcome: SyncOutcome)

/** 全走査の最後の試みの結果（#346）。 */
sealed interface SyncOutcome {
    data object Succeeded : SyncOutcome

    /** 「Wi-Fi のみ」で従量制のネットワークだったので、取得元に問い合わせずに見送った。失敗には数えない。 */
    data object SkippedNotOnWifi : SyncOutcome

    /** 失敗。理由ごとに、同期の結果の文言（スナックバー）と同じ文言を出せるだけの値を持つ。 */
    sealed interface Failure : SyncOutcome

    /** 取得元に到達できない（[SourceException.Unreachable]）。 */
    data object Unreachable : Failure

    /** 取得に失敗（[SourceException.Failed]）。[message] は例外のメッセージ。 */
    data class FetchFailed(val message: String) : Failure

    /** 取り込みに失敗（Room / DataStore / Keystore などの例外）。[exceptionName] は例外のクラス名。 */
    data class ImportFailed(val exceptionName: String) : Failure
}

/** 接続済みの取得元。取得元の種類ごとに持つ情報が違う。 */
sealed interface ConnectedSource {
    data class Jellyfin(val session: Session, val library: SelectedLibrary) : ConnectedSource

    /** NAS の共有フォルダ（SMB）。 */
    data class Smb(val connection: SmbConnection) : ConnectedSource

    /** 端末のフォルダ（SAF、#199）。 */
    data class Device(val folder: DeviceFolder) : ConnectedSource
}

/** 取得元が端末のフォルダか（接続済み、またはアクセスが切れてログアウトした状態。#199）。「Wi-Fi のみ」とローカルネットワークの権限の分岐に使う。 */
val SessionState.isDeviceFolder: Boolean
    get() = when (this) {
        is SessionState.Ready -> source is ConnectedSource.Device
        is SessionState.SignedOut -> lastDeviceFolder != null
        is SessionState.NeedsLibrary -> false
    }

/**
 * 「Wi-Fi のみ」が効くか（#199 の決定 4）。取得元が端末のフォルダなら、端末の中から端末の中へのコピーなので効かせない。
 * Jellyfin と SMB は設定どおり。手動の同期の従量制での中止（`LibraryRefresher`）がこれで決める。Worker のネットワークの制約（`work.requiredNetworkType`）は同じ規則（端末のフォルダには効かせない）を `isDeviceFolder` で自分で見ている。
 */
fun wifiOnlyApplies(wifiOnly: Boolean, state: SessionState): Boolean = wifiOnly && !state.isDeviceFolder

/**
 * 取得元がローカルネットワークを使うか（Jellyfin か SMB。#199 の決定 7）。ログアウト中は、前回の取得元が Jellyfin か SMB なら使う。
 * 端末のフォルダと、まだ取得元を選んでいない人は使わない。
 */
val SessionState.usesLocalNetwork: Boolean
    get() = when (this) {
        is SessionState.Ready -> source !is ConnectedSource.Device
        is SessionState.NeedsLibrary -> true
        is SessionState.SignedOut -> lastServerUrl != null || lastSmb != null
    }

/**
 * 端末のフォルダ（#199）。[treeUri] は `ACTION_OPEN_DOCUMENT_TREE` で選んだツリーの URI（文字列）、[name] は表示用のフォルダ名。
 * 端末のストレージ（内部ストレージと SD カード）のフォルダだけを受け付ける（[isDeviceStorage]）。
 */
data class DeviceFolder(val treeUri: String, val name: String) {
    /**
     * 端末の中の場所（画面に出すパスの元。PR #214 のマージ前の検証で、人の判断で足した）。ツリーの文書 ID
     * （`DocumentsContract.getTreeDocumentId` の値。URI の `/tree/` の後ろを百分率の符号から戻したもの）を `<ボリューム>:<パス>` として読む。
     * 例: `primary:KikidameTest/Radio` → ボリューム `primary`、パス `KikidameTest`・`Radio`。
     *
     * この形は `ExternalStorageProvider`（受け付ける唯一の provider。[isDeviceStorage]）の実装の決まりで、`DocumentsContract` の
     * 公開の契約ではない（文書 ID は provider ごとに不透明とされている）。この形を前提にするのは未確認の前提で、形が崩れていたら null を返し、
     * 呼び出し側は保存済みの表示名（[name]）に戻す。保存（DataStore の `device_*`）は変えず、表示のたびに URI から作る。
     */
    val location: DeviceFolderLocation?
        get() {
            val raw = runCatching { java.net.URI(treeUri).rawPath }.getOrNull() ?: return null
            val encoded = raw.removePrefix("/tree/").takeIf { it != raw && it.isNotEmpty() && '/' !in it } ?: return null
            // URLDecoder は「+」を空白にするので、先に「+」そのものに戻るよう符号にしておく
            val documentId = runCatching { java.net.URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
            val volume = documentId.substringBefore(':', missingDelimiterValue = "")
            if (volume.isEmpty() || volume.contains('/')) return null
            val segments = documentId.substringAfter(':').split('/').filter { it.isNotEmpty() }
            return DeviceFolderLocation(volume, segments)
        }

    companion object {
        /** 内部ストレージと SD カードの文書の provider（`ExternalStorageProvider`）の authority。 */
        const val EXTERNAL_STORAGE_AUTHORITY: String = "com.android.externalstorage.documents"

        /**
         * 選んだツリーが端末のストレージのものか（#199 の決定 3）。authority が [EXTERNAL_STORAGE_AUTHORITY] の `content:` の URI だけ。
         * ほかの provider（Google ドライブ、SAF 経由の SMB など）は受け付けない。
         */
        fun isDeviceStorage(treeUri: String): Boolean {
            val uri = runCatching { java.net.URI(treeUri) }.getOrNull() ?: return false
            return uri.scheme == "content" && uri.authority == EXTERNAL_STORAGE_AUTHORITY
        }
    }
}

/**
 * 端末のフォルダの、端末の中の場所（[DeviceFolder.location]）。[volume] は `ExternalStorageProvider` のボリュームの ID で、
 * 内部ストレージは [PRIMARY_VOLUME]、それ以外（SD カードなど）はボリュームの UUID の形（例 `1234-5678`。実機では未確認）。
 * [segments] はボリュームの根からのフォルダ名（ボリュームの根そのものなら空）。
 */
data class DeviceFolderLocation(val volume: String, val segments: List<String>) {
    val isPrimary: Boolean get() = volume == PRIMARY_VOLUME

    companion object {
        /** 内部ストレージのボリュームの ID（`ExternalStorageProvider` の実装の決まり。公開の契約ではない）。 */
        const val PRIMARY_VOLUME: String = "primary"
    }
}

/** 端末のストレージ以外の provider のフォルダを選んだ（#199 の決定 3）。端末のストレージのフォルダを選び直させる。 */
class UnsupportedFolderException(treeUri: String) : IllegalArgumentException("not a folder on the device storage: $treeUri")

data class Session(
    val serverUrl: String,
    val userName: String,
    val userId: String,
    val accessToken: String,
)

/**
 * SMB の共有への接続の設定（#198）。[host] は名前か IP、[share] は共有名、[path] は共有の中のパス（空なら共有の直下）。
 * [guest] ならゲスト（匿名）接続で、[userName]・[password] は使わない。[password] は保存するとき暗号化する。
 */
data class SmbConnection(
    val host: String,
    val share: String,
    val path: String,
    val userName: String,
    val password: String,
    val guest: Boolean = false,
) {
    override fun toString(): String = "SmbConnection(host=$host, share=$share, path=$path, userName=$userName, guest=$guest)"
}

data class SelectedLibrary(val id: SourceItemId, val name: String)

/** サーバ上のライブラリ（`/UserViews` の 1 件）。音楽ライブラリだけが選べる。 */
data class LibraryView(
    val id: SourceItemId,
    val name: String,
    /** 表示用の種別名（"music" / "movies" など）。不明なら null。 */
    val collectionType: String?,
    val isMusic: Boolean,
)

/** 取得元とのやり取りの失敗。UI はこれで文言を決める。 */
sealed class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * 認証情報が無効（Jellyfin の 401/403、SMB の認証の失敗）、または端末のフォルダの永続権限が無い・取り消された、選んだフォルダ（根）が
     * 無いと分かった（#199 の決定 5）。接続をやり直させる（ログアウトし、端末のフォルダは選び直させる）。
     */
    class Unauthorized(cause: Throwable? = null) : SourceException("unauthorized", cause)

    /** 到達できない（DNS・接続・タイムアウト。共有フォルダの読み取りがやり直しても失敗したときも。端末のフォルダの一時的な失敗を含む）。 */
    class Unreachable(cause: Throwable? = null) : SourceException("unreachable", cause)

    /** それ以外（5xx、想定外のレスポンス）。 */
    class Failed(message: String, cause: Throwable? = null) : SourceException(message, cause)
}

interface SessionRepository {
    val state: Flow<SessionState>

    /** Jellyfin にログインしてセッションを保存する。失敗は [SourceException]。 */
    suspend fun signIn(serverUrl: String, userName: String, password: String)

    /**
     * SMB の共有に接続できるか（共有の直下が読めるか）を確かめ、読めたら保存する（#198）。失敗は [SourceException]:
     * 認証の失敗は [SourceException.Unauthorized]、届かない・共有が読めないのは [SourceException.Unreachable] / [SourceException.Failed]。
     * 保存すると [SessionState.Ready] になる（初回の全走査は呼び出し側が始める）。Jellyfin の接続は消える。
     */
    suspend fun connectSharedFolder(connection: SmbConnection)

    /**
     * 端末のフォルダ（[treeUri] は `ACTION_OPEN_DOCUMENT_TREE` の結果）を取得元にする（#199）。端末のストレージのフォルダでなければ
     * [UnsupportedFolderException]。永続的な読み取りの権限を取り、フォルダの直下が読めるかを確かめてから保存する。読めなければ
     * [SourceException] を投げ、取った権限を手放す。保存すると [SessionState.Ready] になり、ほかの取得元の接続は消える。
     * 読めたら、保存の前に、そのフォルダ以外に持っている永続権限を手放す（保存を最後にする。保存すると画面が離れるため）。
     */
    suspend fun connectDeviceFolder(treeUri: String)

    suspend fun listLibraries(): List<LibraryView>

    suspend fun selectLibrary(library: LibraryView)

    /** 認証情報だけを消す（Jellyfin のトークン、SMB のパスワード。端末のフォルダはログアウトの印を立てる）。手元のデータは残す。 */
    suspend fun signOut()

    /**
     * 全走査の最後の試み（[SessionState.Ready.lastSyncAttempt]）を記録する（#346）。成功なら、同期の失敗の通知を出した記録
     * （[SessionState.Ready.syncFailureNotified]）も消す。ログアウトと取得元を変えたときは、同期の時刻と一緒に消える。
     */
    suspend fun recordSyncAttempt(attempt: SyncAttempt)

    /** 同期の失敗の通知を出したことを記録する（#346）。次に全走査が成功するまで出し直さない。 */
    suspend fun markSyncFailureNotified()
}

/**
 * 取得元の一覧を取り込み、同期する。
 * 全走査は同期そのもの（ADR 0004）。1 番組の同期も、その番組についての一覧が完全なので削除の権限を持つ。
 */
interface LibraryRefreshRepository {
    /**
     * ライブラリ全体を取得し、突合して Room に取り込み、[SyncPlanner] の結論を実行する
     * （保持ルールによる削除、取得元から消えた各回の除去、ダウンロードの予約）。
     * [excluded] が返す各回（再生中の回）は今回は削除しない。走査が長くかかっても今の再生中の回を外すよう、
     * 削除の前（共有フォルダでは番組ごと）に毎回呼び直す（#209）。
     * 401 は [SourceException.Unauthorized] を投げる（呼び出し側がログアウトへ導く）。
     * 取得元に問い合わせる前に試みた時刻（[SessionState.Ready.lastAttemptedAt]）を記録する。失敗しても残る。
     *
     * 共有フォルダでは、タグを読んだ番組をその番組を読み終えるたびに取り込み、その番組の中で削除・予約する（[syncProgram] と同じ範囲。#209）。
     * 途中で失敗・取り消しになっても、取り込み済みの番組は残る。番組の消失の判断と最終同期の時刻の記録は、全部を読み終えたときだけ行う。
     * タグを読む回がある間は、読んだ数を [onProgress] に流す（タグを読む回が無ければ呼ばない）。
     * 番組ごとの同期でダウンロードを予約したら、その数を [onEnqueued] に流す（走査が途中で失敗しても、呼び出し側が
     * ダウンロードの Worker を起こせるように。#209）。返す [RefreshResult.enqueued] はこれも含めた合計。
     */
    suspend fun refresh(
        excluded: () -> Set<EpisodeId> = { emptySet() },
        onProgress: (ScanProgress) -> Unit = {},
        onEnqueued: (Int) -> Unit = {},
    ): RefreshResult

    /**
     * 1 番組だけ同期する。番組の存在を確かめてから（無ければ消失 = 判断保留）その番組の各回一覧を取り、
     * 取り込み → [SyncPlanner] → 除去・削除・予約をこの番組に限って行う。その番組についての一覧は完全なので
     * 削除の権限を持つ（ADR 0004）。最終同期の時刻は更新しない。番組の行が無いか取得元 ID を持たなければ null
     * （各回一覧の「引っ張って更新」の呼び出し側は全体の [refresh] にフォールバックする）。
     */
    suspend fun syncProgram(programId: ProgramId, excluded: Set<EpisodeId> = emptySet()): RefreshResult?
}

/** 全走査でタグを読む回の数 [total] のうち、読み終えた数 [read]（#209）。 */
data class ScanProgress(val read: Int, val total: Int)

data class RefreshResult(
    val programs: Int,
    val episodes: Int,
    val linkedPrograms: Int,
    val linkedEpisodes: Int,
    val fetchedAt: Instant,
    /** 同期で予約したダウンロードの数。 */
    val enqueued: Int = 0,
    /** 保持ルールで消したファイルの数。 */
    val deleted: Int = 0,
    /** 取得元の一覧から消えたため除去した各回の数。 */
    val removed: Int = 0,
    /** 判断保留になった番組の数。 */
    val onHold: Int = 0,
    /** 取り込みで新しく手元に増えた各回の数（同期対象でない番組の回も数える）。突合で結び直した既存の回は数えない（#142）。 */
    val newEpisodes: Int = 0,
) {
    /** 手元に変化（新しい回・ダウンロード予約・削除）があったか。判断保留は変化に数えない（#142）。 */
    val hasChanges: Boolean get() = newEpisodes > 0 || enqueued > 0 || deleted + removed > 0
}

/** 「取得元を変える」（#50）。手元の番組・各回・再生位置・セッションを全部消し、音声ファイルも消す（#50）。 */
interface LocalDataReset {
    suspend fun resetAll()
}
