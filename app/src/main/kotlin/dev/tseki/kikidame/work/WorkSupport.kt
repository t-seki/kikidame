package dev.tseki.kikidame.work

import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkManager
import dev.tseki.kikidame.domain.SessionState
import dev.tseki.kikidame.domain.isDeviceFolder
import kotlinx.coroutines.flow.first

/**
 * 同期とダウンロードの Worker のネットワークの制約。取得元が端末のフォルダなら NOT_REQUIRED（ネットワークの条件をかけない。
 * #199 の「決定の追記」）。Jellyfin と SMB は「Wi-Fi のみ」なら UNMETERED、そうでなければ CONNECTED。
 * 「Wi-Fi のみ」が効くかの規則は domain の `wifiOnlyApplies` と同じ（端末のフォルダには効かせない）で、ここは WorkManager の [NetworkType] への写像。
 * `sync` と `download` の両方が使うので、どちらのパッケージにも置かない（パッケージを循環させない）。
 */
fun requiredNetworkType(wifiOnly: Boolean, state: SessionState): NetworkType = when {
    state.isDeviceFolder -> NetworkType.NOT_REQUIRED
    wifiOnly -> NetworkType.UNMETERED
    else -> NetworkType.CONNECTED
}

fun networkConstraints(wifiOnly: Boolean, state: SessionState): Constraints =
    Constraints.Builder().setRequiredNetworkType(requiredNetworkType(wifiOnly, state)).build()

/** ユニークな Work を取り消し、その Work がすべて終わる（完了・取り消し・失敗）まで待つ。 */
suspend fun WorkManager.cancelUniqueAndAwait(name: String) {
    cancelUniqueWork(name)
    getWorkInfosForUniqueWorkFlow(name).first { infos -> infos.all { it.state.isFinished } }
}
