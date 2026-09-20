package dev.tseki.kikidame.sync

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 手動の更新・同期が「Wi-Fi のみ」に従うための、今のネットワークの見え方。Worker は `Constraints` で待つ。 */
interface NetworkStatus {
    /** 従量制（モバイル回線・テザリング）に繋がっているか。オフラインなら false。 */
    fun isMetered(): Boolean
}

@Singleton
class ConnectivityNetworkStatus @Inject constructor(@ApplicationContext context: Context) : NetworkStatus {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun isMetered(): Boolean = connectivity?.isActiveNetworkMetered == true
}
