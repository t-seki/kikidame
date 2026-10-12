package dev.tseki.kikidame.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.tseki.kikidame.text.UiText

/** [UiText]（`text/`。型は Compose に依存しない）を文字列に解決する。解決するのは `ui/` だけ（ADR 0009）。 */
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
