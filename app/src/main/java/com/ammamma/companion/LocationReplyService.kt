package com.ammamma.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log

/**
 * The grandpa-finder. When find-my-phone fires, this texts the phone's GPS location
 * back to the family member who sent the code word — so they don't just hear it ring,
 * they see WHERE it is on a map. Works with no internet on her side (GPS + SMS).
 *
 * It runs as a brief foreground service: that's the correct Android 8 pattern for
 * doing real work (waiting for a GPS fix) that was kicked off by a broadcast, even
 * if the app wasn't open.
 */
class LocationReplyService : Service() {

    private var locThread: HandlerThread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()

        val recipients = intent?.getStringArrayExtra(EXTRA_RECIPIENTS)?.toList().orEmpty()
        val prefix = intent?.getStringExtra(EXTRA_PREFIX) ?: PREFIX_FIND
        if (recipients.isEmpty() || !hasPermission(Manifest.permission.SEND_SMS)) {
            Log.w(TAG, "No recipients or SEND_SMS permission; cannot send location")
            stopSelf()
            return START_NOT_STICKY
        }
        findLocationThenReply(recipients, prefix)
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun findLocationThenReply(recipients: List<String>, prefix: String) {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            reply(recipients, prefix, null)   // no location permission — still confirm
            stopSelf()
            return
        }

        // Prefer a FRESH fix: the last-known location can be hours (or days) old —
        // exactly wrong for "where is the phone RIGHT NOW". We wait up to 10s for a
        // real fix and only then fall back to whatever cached fix exists.
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            // Location is switched OFF — a stale cached fix is still better than nothing.
            reply(recipients, prefix, bestLastKnown(lm))
            stopSelf()
            return
        }

        val thread = HandlerThread("loc").apply { start() }
        locThread = thread
        val handler = Handler(thread.looper)

        val listener = object : LocationListener {
            var done = false
            override fun onLocationChanged(location: Location) = finishWith(location)
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
            fun finishWith(loc: Location?) {
                if (done) return
                done = true
                runCatching { lm.removeUpdates(this) }
                reply(recipients, prefix, loc ?: bestLastKnown(lm))
                stopSelf()
            }
        }

        lm.requestSingleUpdate(provider, listener, thread.looper)
        handler.postDelayed({ listener.finishWith(null) }, 10_000)
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(lm: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        return providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun reply(recipients: List<String>, prefix: String, loc: Location?) {
        val message = if (loc != null) {
            "$prefix\nhttps://maps.google.com/?q=${loc.latitude},${loc.longitude}"
        } else {
            "$prefix\n(Location not available right now.)"
        }
        val sms = SmsManager.getDefault()
        for (to in recipients) {
            try {
                sms.sendMultipartTextMessage(to, null, sms.divideMessage(message), null, null)
                Log.i(TAG, "Location SMS sent to $to: $message")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send location SMS to $to", e)
            }
        }
    }

    private fun promoteToForeground() {
        val channelId = "finder"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Finder", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, channelId)
            .setContentTitle("Sending location…")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
        startForeground(FOREGROUND_ID, n)
    }

    private fun hasPermission(p: String) =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        locThread?.quitSafely()
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Ammamma"
        private const val EXTRA_RECIPIENTS = "recipients"
        private const val EXTRA_PREFIX = "prefix"
        private const val FOREGROUND_ID = 42

        private const val PREFIX_FIND = "Ammamma phone ikkada undi / is here:"

        /** Find-my-phone: text the location back to whoever sent the code word. */
        fun start(context: Context, sender: String) {
            launch(context, listOf(sender), PREFIX_FIND)
        }

        /** Travel mode: charger plugged in/out -> ping EVERY family number. */
        fun startTravelPing(context: Context, pluggedIn: Boolean) {
            val numbers = Settings.familyNumbers(context)
            if (numbers.isEmpty()) {
                Log.w(TAG, "Travel mode ping skipped: no family numbers saved")
                return
            }
            val what = if (pluggedIn) "plugged IN" else "plugged OUT"
            launch(context, numbers, "Travel mode: charger $what. Ammamma phone is here:")
        }

        private fun launch(context: Context, recipients: List<String>, prefix: String) {
            val i = Intent(context, LocationReplyService::class.java)
                .putExtra(EXTRA_RECIPIENTS, recipients.toTypedArray())
                .putExtra(EXTRA_PREFIX, prefix)
            // Foreground-service start is allowed from a background broadcast on O+.
            context.startForegroundService(i)
        }
    }
}
