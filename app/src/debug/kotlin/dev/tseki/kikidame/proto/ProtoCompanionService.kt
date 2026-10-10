package dev.tseki.kikidame.proto

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * 経路 2: コンパニオンデバイスの存在の通知。Android 16 以降は onDevicePresenceEvent、
 * それより前は onDeviceAppeared で来るはず。どちらで受けたかをログに出す。
 */
class ProtoCompanionService : CompanionDeviceService() {
    @Deprecated("API 33 未満の経路")
    override fun onDeviceAppeared(address: String) {
        Log.i(Proto.TAG, "onDeviceAppeared(String) sdk=${Build.VERSION.SDK_INT}")
        fire("onDeviceAppeared(String)")
    }

    @RequiresApi(33)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        Log.i(Proto.TAG, "onDeviceAppeared(AssociationInfo) sdk=${Build.VERSION.SDK_INT}")
        fire("onDeviceAppeared(AssociationInfo)")
    }

    @RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        Log.i(Proto.TAG, "onDevicePresenceEvent event=${event.event} sdk=${Build.VERSION.SDK_INT}")
        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED ||
            event.event == DevicePresenceEvent.EVENT_BT_CONNECTED ||
            event.event == DevicePresenceEvent.EVENT_SELF_MANAGED_APPEARED
        ) {
            fire("onDevicePresenceEvent(${event.event})")
        }
    }

    private fun fire(via: String) {
        val route = Proto.route(this)
        Log.i(Proto.TAG, "companion presence via=$via route=$route")
        if (route == Proto.ROUTE_COMPANION) Proto.startPlayback(this, "companion:$via")
    }
}
