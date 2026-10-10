package dev.tseki.kikidame.proto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import dev.tseki.kikidame.playback.PlaybackService

/** 試作 #204（マージしない）。2 つの経路が共有する、経路の切り替えと再生の開始。 */
object Proto {
    const val TAG = "Proto204"
    const val ROUTE_OFF = "off"
    const val ROUTE_BROADCAST = "broadcast"
    /** ブロードキャストで受けて、再生はせず準備だけする（経路 1 の別モード） */
    const val ROUTE_PREPARE = "prepare"
    const val ROUTE_COMPANION = "companion"

    private const val PREFS = "proto204"
    private const val KEY_ROUTE = "route"

    fun route(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ROUTE, ROUTE_OFF) ?: ROUTE_OFF

    fun setRoute(context: Context, route: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ROUTE, route).commit()
    }

    /** #119 と同じ道: MediaButtonReceiver がするのと同じ、ACTION_MEDIA_BUTTON + KEYCODE_MEDIA_PLAY の明示 Intent で FGS を始める。 */
    fun startPlayback(context: Context, trigger: String) {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(context, PlaybackService::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        try {
            context.startForegroundService(intent)
            Log.i(TAG, "startForegroundService OK (trigger=$trigger)")
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException など
            Log.e(TAG, "startForegroundService FAILED (trigger=$trigger): ${e.javaClass.name}: ${e.message}")
        }
    }
}
