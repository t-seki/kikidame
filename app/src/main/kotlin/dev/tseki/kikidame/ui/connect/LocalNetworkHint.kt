package dev.tseki.kikidame.ui.connect

import dev.tseki.kikidame.R
import dev.tseki.kikidame.domain.ServerException
import dev.tseki.kikidame.ui.UiText

/**
 * Android 17 のローカルネットワークの権限が要るのに無いか（[dev.tseki.kikidame.LocalNetworkPermission.isRequiredAndMissing]）。
 * 接続画面の ViewModel に `Context` の代わりに注入する（#237。実装は [dev.tseki.kikidame.di.AppModule]）。テストはラムダで渡す。
 */
fun interface LocalNetworkPermissionCheck {
    fun isRequiredAndMissing(): Boolean
}

/**
 * 接続に失敗したときに画面に出す文言。[message] の後に、届かない（[ServerException.Unreachable]）うえにローカルネットワークの権限が
 * 無ければ、権限の案内を続ける。権限は届かないときだけ確かめる。
 */
internal fun ServerException.connectErrorText(message: UiText, permission: LocalNetworkPermissionCheck): UiText {
    val hint = if (this is ServerException.Unreachable && permission.isRequiredAndMissing()) {
        UiText.Res(R.string.connect_hint_local_network_permission)
    } else {
        null
    }
    return UiText.Joined(listOfNotNull(message, hint))
}
