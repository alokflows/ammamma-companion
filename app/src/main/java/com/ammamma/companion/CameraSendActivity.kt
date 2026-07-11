package com.ammamma.companion

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * "Send this picture" — the whole flow in one screen:
 *   camera opens -> she takes the photo -> full-screen preview + one giant green
 *   send button -> WhatsApp opens with the photo attached.
 *
 * Android will never let an app silently pick the person and tap send for her
 * (there is no API for that, by design — it would be a spam vector) — the LAST
 * two taps are always hers. So instead of pretending we can skip them, this
 * screen SPEAKS the exact two taps she still needs to make, right as WhatsApp
 * opens, the same way the rest of the app teaches instead of assumes.
 */
class CameraSendActivity : Activity() {

    private lateinit var ivPreview: ImageView
    private lateinit var reviewButtons: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_send)

        ivPreview = findViewById(R.id.ivPreview)
        reviewButtons = findViewById(R.id.reviewButtons)
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendToWhatsApp() }
        findViewById<Button>(R.id.btnRetake).setOnClickListener {
            reviewButtons.visibility = View.GONE
            launchCamera()
        }
        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            Announcer.get(this).stopSpeaking()
            finish()
        }

        // Only launch the camera on a genuinely fresh start. If the system killed
        // this process while the camera app was in front (real risk on 2 GB RAM) and
        // is now recreating us to deliver the pending result, savedInstanceState is
        // non-null — relaunching the camera here would silently throw that shot away
        // and open a SECOND camera on top of the one already returning a result.
        if (savedInstanceState == null) {
            launchCamera()
        }
    }

    /** Speak, then hand off to the system camera app with EXTRA_OUTPUT pointing at
     *  our own content:// URI (see [ShareFileProvider] for why not a plain file). */
    private fun launchCamera() {
        Announcer.get(this).announce("camera_open", "ఫోటో తీద్దాం, కెమెరా తెరుస్తున్నాను")

        val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (captureIntent.resolveActivity(packageManager) == null) {
            Announcer.get(this).say("కెమెరా దొరకలేదు")
            finish()
            return
        }
        val uri = ShareFileProvider.uriFor()
        captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        // The camera app WRITES the photo, so it needs write (not just read) access
        // to our provider's URI.
        captureIntent.addFlags(
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        startActivityForResult(captureIntent, REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK) {
            showReview()
        } else {
            Announcer.get(this).announce("camera_cancelled", "ఫోటో తీయలేదు")
            finish()
        }
    }

    private fun showReview() {
        val file = ShareFileProvider.fileFor(this)
        if (file == null || !file.exists()) {
            Announcer.get(this).say("ఫోటో దొరకలేదు")
            finish()
            return
        }
        // Downsample to the screen size, never the full camera resolution — a stock
        // photo can be 12+ MP, which as a raw Bitmap risks OOM on a 2 GB device.
        val bmp = decodeSampledBitmap(
            file.absolutePath,
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        )
        if (bmp == null) {
            Announcer.get(this).say("ఫోటో దొరకలేదు")
            finish()
            return
        }
        ivPreview.setImageBitmap(bmp)
        reviewButtons.visibility = View.VISIBLE
        Announcer.get(this).announce("camera_review", "ఫోటో బాగుందా? పంపాలంటే పచ్చ బటన్ నొక్కండి")
    }

    private fun sendToWhatsApp() {
        val uri = ShareFileProvider.uriFor()
        val whatsapp = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (whatsapp.resolveActivity(packageManager) != null) {
            // Speak the lesson FIRST (same speech-first pattern as every other
            // action in this app) — it keeps playing over the app switch since the
            // Announcer is a process-wide singleton, not tied to this Activity.
            Announcer.get(this).announce(
                "camera_send_lesson",
                "వాట్సాప్‌లో మనిషి మీద నొక్కండి, తర్వాత పచ్చ బాణం నొక్కండి"
            )
            startActivity(whatsapp)
        } else {
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "ఫోటో పంపు"
            )
            Announcer.get(this).announce(
                "camera_send_fallback",
                "వాట్సాప్ కనబడలేదు, మనిషిని ఎంచుకుని పంపండి"
            )
            startActivity(chooser)
        }
        finish()
    }

    /** Standard Android decode-at-sample-size recipe: read just the bounds first,
     *  then decode for real at the smallest power-of-two size that still fills the
     *  screen — full quality where it's seen, none of the memory wasted where it
     *  isn't. */
    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        var halfHeight = bounds.outHeight / 2
        var halfWidth = bounds.outWidth / 2
        while (halfHeight / sample >= reqHeight && halfWidth / sample >= reqWidth) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, opts)
    }

    companion object {
        private const val REQ_CAPTURE = 4001
    }
}
