package dev.tseki.jellyfinradio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.tseki.jellyfinradio.domain.ThemeMode

/**
 * 固定パレット（#54、docs/ui.md の「色」）。温かい黒にアンバーのアクセント。ダークが主で、ライトも同じ構成（既定は端末の設定に追従、設定で固定もできる #62）。
 * 状態を表す色は primary（よく聴く・同期対象・再生中）と error（消失・失敗・破壊的操作）の 2 系統だけ（聴いている回の行の背景だけ secondaryContainer）。
 * background は Scaffold の地なので surface と同じ値にする（既定のままだと Material3 の素の色が透ける）。下に無いスロットは Material3 の既定のまま。dynamic color は使わない（アプリの個性は色で出す）。
 */
private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFF5A623),
    onPrimary = Color(0xFF3D2600),
    primaryContainer = Color(0xFF5A3A00),
    onPrimaryContainer = Color(0xFFFFE2B8),
    secondaryContainer = Color(0xFF3A2D15),
    onSecondaryContainer = Color(0xFFF2DDB8),
    surface = Color(0xFF141210),
    onSurface = Color(0xFFEDE5DA),
    background = Color(0xFF141210),
    onBackground = Color(0xFFEDE5DA),
    onSurfaceVariant = Color(0xFFB3A58F),
    surfaceContainerLow = Color(0xFF1B1815),
    surfaceContainer = Color(0xFF211D18),
    surfaceContainerHigh = Color(0xFF2B261F),
    surfaceContainerHighest = Color(0xFF35302A),
    error = Color(0xFFFF7A68),
    onError = Color(0xFF3B0906),
    outline = Color(0xFF7A6E5C),
    outlineVariant = Color(0xFF4A4136),
)

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF9A5B00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDB3),
    onPrimaryContainer = Color(0xFF2E1B00),
    secondaryContainer = Color(0xFFF3E3C8),
    onSecondaryContainer = Color(0xFF2E2312),
    surface = Color(0xFFFBF7F1),
    onSurface = Color(0xFF1F1A14),
    background = Color(0xFFFBF7F1),
    onBackground = Color(0xFF1F1A14),
    onSurfaceVariant = Color(0xFF5E5347),
    surfaceContainerLow = Color(0xFFF5F0E8),
    surfaceContainer = Color(0xFFEFE9DF),
    surfaceContainerHigh = Color(0xFFE8E1D5),
    surfaceContainerHighest = Color(0xFFE1D9CC),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFF8A7D6C),
    outlineVariant = Color(0xFFD6CCBC),
)

/** 数字を等幅（tnum）にする。`未再生 3`・`1:23:45`・`12.3 GB` のように数が並ぶ場面が多く、桁が揺れないように全スタイルに付ける。書体はシステムのまま。 */
private val TabularTypography: Typography = Typography().let { t ->
    val tnum = "tnum"
    Typography(
        displayLarge = t.displayLarge.copy(fontFeatureSettings = tnum),
        displayMedium = t.displayMedium.copy(fontFeatureSettings = tnum),
        displaySmall = t.displaySmall.copy(fontFeatureSettings = tnum),
        headlineLarge = t.headlineLarge.copy(fontFeatureSettings = tnum),
        headlineMedium = t.headlineMedium.copy(fontFeatureSettings = tnum),
        headlineSmall = t.headlineSmall.copy(fontFeatureSettings = tnum),
        titleLarge = t.titleLarge.copy(fontFeatureSettings = tnum),
        titleMedium = t.titleMedium.copy(fontFeatureSettings = tnum),
        titleSmall = t.titleSmall.copy(fontFeatureSettings = tnum),
        bodyLarge = t.bodyLarge.copy(fontFeatureSettings = tnum),
        bodyMedium = t.bodyMedium.copy(fontFeatureSettings = tnum),
        bodySmall = t.bodySmall.copy(fontFeatureSettings = tnum),
        labelLarge = t.labelLarge.copy(fontFeatureSettings = tnum),
        labelMedium = t.labelMedium.copy(fontFeatureSettings = tnum),
        labelSmall = t.labelSmall.copy(fontFeatureSettings = tnum),
    )
}

/** [themeMode] が SYSTEM なら端末の設定に追従、それ以外は指定どおり（#62）。 */
@Composable
fun JellyfinRadioTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = TabularTypography,
        content = content,
    )
}
