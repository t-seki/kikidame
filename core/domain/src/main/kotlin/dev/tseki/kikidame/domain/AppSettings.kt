package dev.tseki.kikidame.domain

import kotlinx.coroutines.flow.Flow

/**
 * アプリ全体の設定。番組ごとには持たないもの。セッション（サーバ・ライブラリ）とは別で、
 * 「別のサーバに接続」でも消えない。
 */
interface AppSettingsRepository {
    /** ダウンロードと同期を Wi-Fi（従量制でない回線）に限る。既定 ON。 */
    val wifiOnly: Flow<Boolean>
    suspend fun setWifiOnly(value: Boolean)

    /** 倍速。[PlaybackSpeed.CHOICES] のどれか。既定 1.0。 */
    val playbackSpeed: Flow<Float>
    suspend fun setPlaybackSpeed(value: Float)
    /** テーマ（#62）。既定は [ThemeMode.SYSTEM]。 */
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(value: ThemeMode)
}
/** ダーク／ライトの選び方（#62）。SYSTEM は端末の設定に追従。 */
enum class ThemeMode {
    SYSTEM, DARK, LIGHT;
    companion object {
        /** 保存された名前から。不明な値（将来の項目や壊れた値）は [SYSTEM] に丸める。 */
        fun fromStorageName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
