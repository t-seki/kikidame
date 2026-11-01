package dev.tseki.kikidame

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import dev.tseki.kikidame.domain.AppSettingsRepository
import dev.tseki.kikidame.domain.SessionRepository
import dev.tseki.kikidame.domain.ThemeMode
import dev.tseki.kikidame.domain.usesLocalNetwork
import dev.tseki.kikidame.ui.KikidameNavHost
import dev.tseki.kikidame.ui.theme.KikidameTheme
import dev.tseki.kikidame.ui.theme.isDark
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: AppSettingsRepository
    @Inject lateinit var sessionRepository: SessionRepository
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* 拒否されても手元の再生はできる */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRuntimePermissionsIfNeeded()
        setContent {
            // テーマの設定を読むまで何も出さない（読めてから描けば既定色で一度描いてから切り替わる「ちらつき」が無い）。
            // この後 NavHost もセッションを読むまで描かないので待ちは直列になるが、どちらも小さな DataStore の読み込みで一瞬
            val themeMode by produceState<ThemeMode?>(initialValue = null) { settings.themeMode.collect { value = it } }
            val mode = themeMode ?: return@setContent
            // システムバーの文字色は enableEdgeToEdge() が端末のダーク設定で決めるので、アプリのテーマが端末と違うとき
            // （ライト固定など）に合わなくなる。テーマが決まるたびにアプリの明暗で呼び直す
            val dark = mode.isDark()
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            KikidameTheme(themeMode = mode) {
                KikidameNavHost()
            }
        }
    }

    /**
     * - Android 13+: 通知の表示に `POST_NOTIFICATIONS` が要る。無いと再生通知が出ない
     * - Android 17+（targetSdk 37）: LAN 内のサーバへ接続するのに `ACCESS_LOCAL_NETWORK` が要る。
     *   無いと LAN 宛の TCP が黙って落ちる（DNS だけは通る）。取得元が Jellyfin か SMB のとき（ログアウト中で前回のものがあるときを含む）だけ
     *   起動時に要求する。端末のフォルダの人には要求しない。まだ取得元を選んでいない人には、取得元の選択で
     *   Jellyfin か SMB を選んだときに要求する（[dev.tseki.kikidame.ui.source.SourcePickScreen]）
     */
    private fun requestRuntimePermissionsIfNeeded() {
        lifecycleScope.launch {
            val usesNetwork = sessionRepository.state.first().usesLocalNetwork
            val missing = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (usesNetwork && LocalNetworkPermission.isRequiredAndMissing(this@MainActivity)) {
                    add(LocalNetworkPermission.NAME)
                }
            }
            if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

/** Android 17 のローカルネットワーク権限。定数は API 37 の `Manifest.permission.ACCESS_LOCAL_NETWORK`。 */
object LocalNetworkPermission {
    const val NAME = "android.permission.ACCESS_LOCAL_NETWORK"
    const val MIN_SDK = 37

    fun isRequiredAndMissing(context: android.content.Context): Boolean =
        Build.VERSION.SDK_INT >= MIN_SDK &&
            ContextCompat.checkSelfPermission(context, NAME) != PackageManager.PERMISSION_GRANTED
}
