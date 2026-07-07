package com.ammamma.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the companion back to life after the phone reboots (or after the app
 * is updated). Without this, one restart and Ammamma's talking partner is gone
 * until someone opens the app by hand — which she may never do.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        Log.i(TAG, "Boot/update ($action) -> restarting companion service")
        val i = Intent(context, CompanionService::class.java)
        context.startForegroundService(i)
    }

    companion object {
        private const val TAG = "Ammamma"
    }
}
