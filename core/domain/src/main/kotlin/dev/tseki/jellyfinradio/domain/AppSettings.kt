package dev.tseki.jellyfinradio.domain

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
}
