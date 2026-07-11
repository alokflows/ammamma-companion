package com.ammamma.companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.SmsManager
import android.widget.Button
import android.widget.TextView

/**
 * Grandpa-phone mode: the whole app becomes this screen — ONE giant
 * "find her phone" button. Tapping it texts the code word to Ammamma's
 * number, which makes her phone ring loudly and text back its location.
 *
 * Every outcome is spoken AND shown as a status line, so grandpa never
 * has to guess whether the tap worked. The tiny gear in the corner is the
 * family's way back into Settings (without it a finder phone could never
 * be reconfigured).
 */
class FinderActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_finder)

        status = findViewById(R.id.finderStatus)

        findViewById<Button>(R.id.findHerButton).setOnClickListener { findHer() }

        findViewById<Button>(R.id.finderGear).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            })
        }
    }

    /** Speak the line and show it on the status row — always both, so the
     *  outcome is clear whether he hears it or reads it. */
    private fun tell(text: String, important: Boolean = false) {
        status.text = text
        Announcer.get(this).say(text, important = important)
    }

    private fun findHer() {
        val number = Settings.herNumber(this)
        if (number.isBlank()) {
            tell("ముందు సెట్టింగ్స్‌లో ఆమె నంబర్ పెట్టండి")
            return
        }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            // The manifest declares SEND_SMS; ask for it right here on tap.
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), REQ_SEND_SMS)
            return
        }
        sendFindSms(number)
    }

    private fun sendFindSms(number: String) {
        try {
            SmsManager.getDefault().sendTextMessage(number, null, Settings.codeWord(this), null, null)
            tell("ఆమె ఫోన్ మోగుతుంది, లొకేషన్ SMS వస్తుంది", important = true)
        } catch (e: Exception) {
            // Be honest: a silent failure would leave him waiting for a ring
            // that never comes.
            android.util.Log.e("Ammamma", "Finder SMS failed", e)
            tell("SMS పంపలేకపోయాం", important = true)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode != REQ_SEND_SMS) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            findHer()
        } else {
            // Denied: explain on-screen what the family must fix.
            tell("SMS అనుమతి లేదు — సెట్టింగ్స్‌లో SMS పంపడం అనుమతించండి")
        }
    }

    companion object {
        private const val REQ_SEND_SMS = 301
    }
}
