package dev.tseki.kikidame.ui.connect

import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.SourceException
import dev.tseki.kikidame.text.UiText

/** 接続画面の文言（文言は ui の持ち物なので ui/ に置く）。 */
fun SourceException.toUserMessage(): UiText = when (this) {
    is SourceException.Unauthorized -> UiText.Res(R.string.sync_error_unauthorized)
    is SourceException.Unreachable -> UiText.Res(R.string.sync_error_unreachable_connect)
    is SourceException.Failed -> UiText.Res(R.string.sync_error_server_failed, message.orEmpty())
}
