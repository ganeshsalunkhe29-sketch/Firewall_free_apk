package com.example.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("BootReceiver", "onReceive action = ${intent.action}")
        if (Intent.ACTION_BOOT_COMPLETED == intent.action || 
            "android.intent.action.QUICKBOOT_POWERON" == intent.action) {
            
            // Auto-start VPN on boot completed if it is supposed to be active
            if (VpnController.getVpnServiceSavedState(context)) {
                try {
                    VpnController.startVpn(context)
                    Log.d("BootReceiver", "Automatically started VPN on boot completed.")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to start VPN on boot: ${e.message}")
                }
            }
        }
    }
}
