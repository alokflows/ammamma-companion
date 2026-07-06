package com.ammamma.companion

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

/**
 * Family-only settings. Grandma never needs this; the gear on the home screen
 * opens it for whoever set up the phone.
 *
 * Saving also asks for SMS permission if needed, so find-my-phone actually works.
 */
class SettingsActivity : Activity() {

    private lateinit var codeWord: EditText
    private lateinit var familyNumbers: EditText
    private lateinit var aiKey: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        codeWord = findViewById(R.id.codeWord)
        familyNumbers = findViewById(R.id.familyNumbers)
        aiKey = findViewById(R.id.aiKey)

        // Pre-fill with whatever is saved.
        codeWord.setText(Settings.codeWordRaw(this))
        familyNumbers.setText(Settings.familyNumbersRaw(this))
        aiKey.setText(Settings.aiKey(this))

        findViewById<Button>(R.id.save).setOnClickListener { save() }
    }

    private fun save() {
        Settings.save(
            this,
            codeWord.text.toString(),
            familyNumbers.text.toString(),
            aiKey.text.toString()
        )
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()

        // Find-my-phone + grandpa finder need: read incoming SMS, send the location
        // SMS back, and read GPS. Ask for whatever isn't granted yet.
        val needed = listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_SMS)
        } else {
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQ_SMS) finish()
    }

    companion object {
        private const val REQ_SMS = 201
    }
}
