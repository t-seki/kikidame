package dev.tseki.kikidame.data.source

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/**
 * 1 番組の同期の区間ごとの時間（ミリ秒）と各回の本数。同期の側（`RoomLibraryRefreshRepository.syncProgram`）がコルーチンの文脈に載せ、
 * 取得元（Jellyfin のブックのライブラリ）は文脈にあればそこへ書く（[current]）。同期 1 回につき [summary] を logcat に 1 行だけ出す。
 *
 * 中身は数字と名札だけ（番組名・各回のタイトル・取得元 ID は持たない）。同期の結果には効かない。
 * 取得元の区間は、通らなかった区間は 0 のまま。
 */
class ProgramSyncTimings : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProgramSyncTimings> {
        /** 今の文脈の計測。1 番組の同期の外（全走査など）では null。 */
        suspend fun current(): ProgramSyncTimings? = currentCoroutineContext()[Key]
    }

    /** 手元が知っているその番組の各回の数（取得元へ渡した数）。 */
    @Volatile var knownEpisodes = 0

    /** 既知の各回を `ids=` で引いて親を得る取得の時間と、引いた本数。 */
    @Volatile var lookupMs = 0L

    @Volatile var lookupItems = 0

    /** 親の直下の取得の時間と、取れた本数・頁の数。 */
    @Volatile var parentListMs = 0L

    @Volatile var parentListed = 0

    @Volatile var parentPages = 0

    /** 親の直下の頁を並べて投げる数の上限。 */
    @Volatile var parentConcurrency = 0

    /** 親の直下からその番組に選び直した本数。 */
    @Volatile var programListed = 0

    /** 親のフォルダの取得の時間。 */
    @Volatile var folderMs = 0L

    /** ライブラリ全体の一覧に切り替えたか（0 / 1）と、その取得の時間。 */
    @Volatile var fullListing = 0

    @Volatile var fullListingMs = 0L

    /** 取得元からの取得の全体の時間と、取れた各回の本数。 */
    @Volatile var fetchMs = 0L

    @Volatile var episodes = 0

    /** 手元の取り込み（apply）の時間。 */
    @Volatile var applyMs = 0L

    /** 同期の各段（再生の共有の突き合わせ・削除と予約・番組の画像）の時間。 */
    @Volatile var reconcileMs = 0L

    @Volatile var synchronizeMs = 0L

    @Volatile var imagesMs = 0L

    /** 1 番組の同期の全体の時間。 */
    @Volatile var totalMs = 0L

    /** logcat に出す 1 行。数字と名札だけ。 */
    fun summary(): String = buildString {
        append("program sync timings:")
        append(" total=").append(totalMs)
        append(" fetch=").append(fetchMs)
        append(" lookup=").append(lookupMs).append(" lookupItems=").append(lookupItems)
        append(" parentList=").append(parentListMs).append(" parentListed=").append(parentListed).append(" parentPages=").append(parentPages)
        append(" parentConcurrency=").append(parentConcurrency)
        append(" programListed=").append(programListed)
        append(" folder=").append(folderMs)
        append(" fullListing=").append(fullListing).append(" fullListingMs=").append(fullListingMs)
        append(" apply=").append(applyMs)
        append(" reconcile=").append(reconcileMs)
        append(" synchronize=").append(synchronizeMs)
        append(" images=").append(imagesMs)
        append(" known=").append(knownEpisodes)
        append(" episodes=").append(episodes)
    }
}

/** [block] の経過時間をミリ秒で [record] に渡し、[block] の値を返す。 */
inline fun <T> timed(record: (Long) -> Unit, block: () -> T): T {
    val mark = TimeSource.Monotonic.markNow()
    try {
        return block()
    } finally {
        record(mark.elapsedNow().inWholeMilliseconds)
    }
}
