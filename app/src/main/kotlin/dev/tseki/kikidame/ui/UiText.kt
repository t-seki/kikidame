package dev.tseki.kikidame.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * Composable の外で作る文言（ADR 0009）。ViewModel・`LibraryRefresher`・`NowPlaying`・`EpisodeDetails` は `String` ではなく
 * これを返し、表示する Composable が [resolve] で `strings.xml` から引く。文字列に解決するのは `ui/` の中だけで、
 * `Context.getString` を ViewModel や Service に書かない（例外は `PlaybackService`。通知と Android Auto へは `String` を渡すしかない）。
 * 書式引数（[Res.args] / [Plural.args]）は `String` / `Int` などの値だけで、[UiText] は入れない（文言の入れ子は [Joined] で表す）。
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
    is UiText.Res -> stringResource(id, *args.toTypedArray())
    is UiText.Plural -> pluralStringResource(id, quantity, *args.ifEmpty { listOf(quantity) }.toTypedArray())
    is UiText.Joined -> parts.map { it.resolve() }.joinToString(separator.resolve())
}

/**
 * `ui/` の Composable から呼ぶ非 Composable なラムダ（`LaunchedEffect` でスナックバーに渡す、クリップボードのラベル）用。
 * [context] は `LocalContext.current`。`ui/` の外（ViewModel・Service・Worker）からは呼ばない。
 */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Plain -> text
    is UiText.Res -> context.getString(id, *args.toTypedArray())
    is UiText.Plural -> context.resources.getQuantityString(id, quantity, *args.ifEmpty { listOf(quantity) }.toTypedArray())
    is UiText.Joined -> parts.joinToString(separator.resolve(context)) { it.resolve(context) }
}
