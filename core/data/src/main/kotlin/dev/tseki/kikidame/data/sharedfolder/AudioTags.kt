package dev.tseki.kikidame.data.sharedfolder

import kotlinx.datetime.LocalDate
import kotlin.time.Duration

/** 音声ファイルのタグから読んだ値（#197）。どれも読めなければ null。 */
data class AudioTags(
    val title: String? = null,
    /** タグの日付の文字列そのまま。解釈は [TagDate.parse]。 */
    val date: String? = null,
    val artist: String? = null,
    val duration: Duration? = null,
)

/**
 * 共有フォルダの 1 ファイルのタグを読む（#197）。Android では [RetrieverTagReader]。テストではフェイクにする。
 * ファイルを解釈できない（音声として読めない）ときは空の [AudioTags] を返す。
 * 木の読み取り（[FolderTree.read]）が失敗したときは、その例外を投げる（到達不能として扱う。タグ無しとして保存しない）。
 */
fun interface TagReader {
    fun read(tree: FolderTree, path: String, sizeBytes: Long): AudioTags
}

/** タグの日付の文字列の解釈（#197、epic #195 の決定 9）。 */
object TagDate {
    private val ISO = Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:\D.*)?$""")
    private val COMPACT = Regex("""^(\d{4})(\d{2})(\d{2})(?:T.*)?$""")

    /**
     * 年月日まである日付だけを [LocalDate] にする。年だけ・年月だけ・読めない文字列は null（取り込み日時で代用する）。
     * 時刻やタイムゾーンが付いていても日付の部分だけを取る（Jellyfin の `PremiereDate` と同じく、日付をそのまま使う）。
     * `1904-01-01`（MP4 の日時の起点。日時が 0 のファイルで返ると見込む。実機で未確認）は日付が無いものとして扱う。
     */
    fun parse(raw: String?): LocalDate? {
        val s = raw?.trim().orEmpty()
        val m = ISO.matchEntire(s) ?: COMPACT.matchEntire(s) ?: return null
        val (y, mo, d) = m.destructured
        val date = runCatching { LocalDate(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull() ?: return null
        return date.takeUnless { it == MP4_EPOCH }
    }

    private val MP4_EPOCH = LocalDate(1904, 1, 1)
}
