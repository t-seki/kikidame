package dev.tseki.kikidame.proto

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 経路 1: A2DP の接続状態の変化を受け、STATE_CONNECTED なら再生を始める。 */
class A2dpConnectedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
        val route = Proto.route(context)
        Log.i(Proto.TAG, "A2DP broadcast: action=${intent.action} state=$state route=$route")
        if (intent.action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) return
        if (state != BluetoothProfile.STATE_CONNECTED) return
        if (route != Proto.ROUTE_BROADCAST) return
        Proto.startPlayback(context, "broadcast")
    }
}
