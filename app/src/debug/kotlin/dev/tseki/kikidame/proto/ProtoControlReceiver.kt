package dev.tseki.kikidame.proto

import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * adb から操作する。
 *  - 経路の切り替え: am broadcast -a dev.tseki.kikidame.proto.SET_ROUTE --es route off|broadcast|companion
 *  - 関連付け済みの機器すべての存在監視を始める: am broadcast -a dev.tseki.kikidame.proto.OBSERVE
 *    （関連付けは adb shell cmd companiondevice associate 0 <package> <MAC>）
 */
class ProtoControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "dev.tseki.kikidame.proto.SET_ROUTE" -> {
                val route = intent.getStringExtra("route") ?: Proto.ROUTE_OFF
                Proto.setRoute(context, route)
                Log.i(Proto.TAG, "route set to $route")
            }
            "dev.tseki.kikidame.proto.OBSERVE" -> observe(context)
        }
    }

    private fun observe(context: Context) {
        if (Build.VERSION.SDK_INT < 33) {
            Log.e(Proto.TAG, "observe: sdk < 33 is not handled in this prototype")
            return
        }
        val cdm = context.getSystemService(CompanionDeviceManager::class.java)
        val associations = cdm.myAssociations
        Log.i(Proto.TAG, "observe: ${associations.size} association(s)")
        for (a in associations) {
            try {
                if (Build.VERSION.SDK_INT >= 36) {
                    cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(a.id).build())
                } else {
                    @Suppress("DEPRECATION")
                    cdm.startObservingDevicePresence(a.deviceMacAddress!!.toString())
                }
                Log.i(Proto.TAG, "observe: started for association ${a.id}")
            } catch (e: Exception) {
                Log.e(Proto.TAG, "observe: FAILED for association ${a.id}: ${e.javaClass.name}: ${e.message}")
            }
        }
    }
}
