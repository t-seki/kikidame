package dev.tseki.kikidame.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * Composable の外で作る文言（ADR 0009）。ViewModel・`LibraryRefresher`・`NowPlaying` は `String` ではなくこれを返し、
 * 表示する Composable が [resolve] で `strings.xml` から引く。`Context.getString` は Composable の外に書かない。
 * 引数（[Res.args] 等）に [UiText] を入れると、解決時に再帰的に文字列にする（「配信元名に一致する番組がありません」の配信元名など）。
 * data class なので、テストは `assertEquals(UiText.Res(R.string.x, 12), actual)` で「どの文言が、どの引数で選ばれたか」を比較できる。
 */
sealed interface UiText {
    /** サーバから来た名前など、翻訳しない文字列。 */
    data class Plain(val text: String) : UiText

    data class Res(@StringRes val id: Int, val args: List<Any>) : UiText {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }

    /** `<plurals>`。[args] が空なら [quantity] をそのまま書式の引数にする（`%d 回` の典型）。 */
    data class Plural(@PluralsRes val id: Int, val quantity: Int, val args: List<Any>) : UiText {
        constructor(@PluralsRes id: Int, quantity: Int, vararg args: Any) : this(id, quantity, args.toList())
    }

    /** 複数の文言を [separator] でつなぐ（同期の結果の「。」「、」など、区切りも言語で変わる）。 */
    data class Joined(val parts: List<UiText>, val separator: UiText = Plain("")) : UiText
}

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Plain -> text
    is UiText.Res -> stringResource(id, *resolveArgs(args))
    is UiText.Plural -> pluralStringResource(id, quantity, *resolveArgs(args.ifEmpty { listOf(quantity) }))
    is UiText.Joined -> parts.map { it.resolve() }.joinToString(separator.resolve())
}

@Composable
private fun resolveArgs(args: List<Any>): Array<Any> = args.map { if (it is UiText) it.resolve() else it }.toTypedArray()

/**
 * Composable の外だが Composable の中から呼ぶ場所（`LaunchedEffect` でスナックバーに渡す、クリップボードのラベル）用。
 * [context] は `LocalContext.current`。画面の外（ViewModel・Service）からは呼ばない。
 */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Plain -> text
    is UiText.Res -> context.getString(id, *resolveArgs(context, args))
    is UiText.Plural -> context.resources.getQuantityString(id, quantity, *resolveArgs(context, args.ifEmpty { listOf(quantity) }))
    is UiText.Joined -> parts.joinToString(separator.resolve(context)) { it.resolve(context) }
}

private fun resolveArgs(context: Context, args: List<Any>): Array<Any> = args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray()
