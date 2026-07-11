package com.ammamma.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Recorder Studio — the family records real-voice clips for every line the app
 * speaks. Announcer already prefers a clip in filesDir/clips/<key>.* over TTS,
 * so this screen's ONLY job is putting the right files there (via ClipStore) —
 * zero changes anywhere else make the whole app speak with a family voice.
 *
 * UI rules for the family member holding the phone:
 *  - one row per spoken line: Telugu label + the exact words to say,
 *  - 🎤 starts recording (turns into a red ⏹ stop), ▶/✕ appear once a clip
 *    exists, and the row turns light green,
 *  - only ONE recording or playback at a time — starting either stops the other.
 *
 * Special: the "goodmorning" clip doubles as the opt-in for the morning
 * heartbeat — a TTS "good morning" would be a cold way to wake up, so the
 * heartbeat turns on only when a real voice exists, and off when it's deleted.
 */
class RecorderActivity : Activity() {

    /** One recordable line: the Announcer event key, a Telugu label the family
     *  understands, and the words they should actually say into the mic. */
    private data class ClipSpec(val key: String, val label: String, val hint: String)

    /** Live view references for one row so it can be refreshed after changes. */
    private data class Row(
        val spec: ClipSpec,
        val root: View,
        val record: Button,
        val play: Button,
        val delete: Button
    )

    private val rows = LinkedHashMap<String, Row>()

    // Recording / playback state. Single instances enforce "only one at a time".
    private var recorder: MediaRecorder? = null
    private var recordingKey: String? = null
    private var player: MediaPlayer? = null
    private var playingKey: String? = null

    /** Row whose record button was tapped before the mic permission existed —
     *  recording starts right after the user grants it, no second tap needed. */
    private var pendingRecordKey: String? = null

    // Recordings land in cache first; only a clean stop() moves them into clips/.
    // A crash mid-recording therefore never leaves a corrupt clip for Announcer.
    private val tempFile: File get() = File(cacheDir, "recorder_tmp.m4a")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recorder)
        buildCatalog(findViewById(R.id.sectionsContainer))
    }

    /** Stop everything when the screen goes away — a MediaRecorder left running
     *  in the background would hold the mic hostage for the whole phone. */
    override fun onPause() {
        super.onPause()
        stopRecording(save = true)   // keep what was said so far rather than lose it
        stopPlayback()
    }

    // ---------------------------------------------------------------- catalog

    private fun buildCatalog(container: LinearLayout) {
        // 1. Greetings — hints copied verbatim from the current TTS lines.
        addSection(container, "పలకరింపులు · Greetings",
            "The friendly lines she hears when opening screens.")
        addRow(container, ClipSpec("home_greeting", "ఇంటి పలకరింపు · Home", "నమస్తే అమ్మమ్మ"))
        addRow(container, ClipSpec("talk_greeting", "మాట్లాడు తెర · Talk screen", "చెప్పమ్మా, ఏం కావాలి"))
        addRow(container, ClipSpec("goodmorning", "శుభోదయం · Good morning", "శుభోదయం అమ్మమ్మ"))

        // 2. Battery — the TTS versions speak the exact %, a clip can't, so the
        //    hints are the same sentences without the number.
        addSection(container, "బ్యాటరీ & ఛార్జింగ్ · Battery",
            "Charger in/out and charge warnings.")
        addRow(container, ClipSpec("charger_connected", "ఛార్జర్ పెట్టినప్పుడు · Plugged in", "ఛార్జర్ పెట్టారు"))
        addRow(container, ClipSpec("charger_removed", "ఛార్జర్ తీసినప్పుడు · Unplugged", "ఛార్జర్ తీసేశారు"))
        addRow(container, ClipSpec("battery_full", "ఛార్జ్ నిండినప్పుడు · Full", "ఛార్జింగ్ నిండింది, ఛార్జర్ తీసేయండి"))
        addRow(container, ClipSpec("battery_low", "ఛార్జ్ తక్కువప్పుడు · Low", "ఛార్జ్ తక్కువగా ఉంది, దయచేసి ఛార్జ్ చేయండి"))

        // 3. Find-phone.
        addSection(container, "ఫోన్ వెతుకు · Find phone",
            "Spoken while the phone rings out loud to be found.")
        addRow(container, ClipSpec("found_phone", "ఫోన్ దొరికినప్పుడు · Found it", "ఫోన్ ఇక్కడ ఉంది అమ్మమ్మ!"))

        // 4. Talking alarm.
        addSection(container, "అలారం · Alarm",
            "The talking-alarm line at the set time.")
        addRow(container, ClipSpec("alarm", "అలారం మాట · Alarm line", "అమ్మమ్మ, సమయం అయింది"))

        // 5. Hourly chime — one clip per hour of the day, labeled in the
        //    12-hour Telugu form she actually thinks in.
        addSection(container, "గంట గంటకి · Hourly chime",
            "One line per hour; record only the hours you want a real voice for.")
        for (h in 0..23) {
            val h12 = if (h % 12 == 0) 12 else h % 12
            val gantalu = if (h12 == 1) "1 గంట" else "$h12 గంటలు"
            addRow(container, ClipSpec(
                "hour_$h",
                "సమయం $gantalu (${periodTelugu(h)})",
                "ఇప్పుడు ${periodTelugu(h)} $gantalu"
            ))
        }

        // 6. Callers — keyed by the STABLE contact id (never the list position),
        //    so a clip keeps working after the family reorders or edits people.
        addSection(container, "ఎవరు ఫోన్ చేస్తున్నారు · Callers",
            "Announced while the phone rings — one per person.")
        Contacts.load(this).forEach { c ->
            addRow(container, ClipSpec(
                "caller_${c.id}",
                "${c.name} · ${c.english}",
                "${c.name} ఫోన్ చేస్తున్నారు"
            ))
        }
        addRow(container, ClipSpec("caller_device", "ఫోన్‌లో సేవ్ అయిన వ్యక్తి · Other saved contact",
            "తెలిసినవారు ఫోన్ చేస్తున్నారు"))
        addRow(container, ClipSpec("caller_unknown", "తెలియని నంబర్ · Unknown number",
            "ఎవరో ఫోన్ చేస్తున్నారు"))
    }

    /** Day-part words matching how the hours are spoken at home. */
    private fun periodTelugu(h: Int): String = when (h) {
        in 4..5 -> "తెల్లవారుజాము"   // early dawn
        in 6..11 -> "ఉదయం"          // morning
        in 12..15 -> "మధ్యాహ్నం"     // afternoon
        in 16..18 -> "సాయంత్రం"      // evening
        else -> "రాత్రి"             // night (19–23 and 0–3)
    }

    private fun addSection(container: LinearLayout, title: String, helper: String) {
        val t = TextView(this).apply {
            text = title
            setTextColor(0xFF5A3720.toInt())
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        container.addView(t, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) })

        val h = TextView(this).apply {
            text = helper
            setTextColor(0xFF8A7361.toInt())
            textSize = 13f
        }
        container.addView(h, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun addRow(container: LinearLayout, spec: ClipSpec) {
        val v = layoutInflater.inflate(R.layout.item_clip_row, container, false)
        v.findViewById<TextView>(R.id.clipLabel).text = spec.label
        v.findViewById<TextView>(R.id.clipHint).text = "చెప్పండి: “${spec.hint}”"

        val row = Row(
            spec, v,
            v.findViewById(R.id.btnRecord),
            v.findViewById(R.id.btnPlay),
            v.findViewById(R.id.btnDelete)
        )
        row.record.setOnClickListener { onRecordTapped(spec.key) }
        row.play.setOnClickListener { onPlayTapped(spec.key) }
        row.delete.setOnClickListener { confirmDelete(spec) }

        container.addView(v, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        rows[spec.key] = row
        refreshRow(row)
    }

    /** Paint one row from disk truth: green + ▶/✕ when a clip exists. */
    private fun refreshRow(row: Row) {
        val recorded = ClipStore.has(this, row.spec.key)
        val isRecording = recordingKey == row.spec.key
        val isPlaying = playingKey == row.spec.key

        row.root.setBackgroundResource(
            if (recorded && !isRecording) R.drawable.card_clip_done else R.drawable.card_face
        )
        row.record.text = if (isRecording) "⏹" else "🎤"
        row.record.setBackgroundResource(
            if (isRecording) R.drawable.btn_danger_red else R.drawable.btn_primary_green
        )
        // While recording, hide ▶/✕ so the only possible tap is "stop".
        row.play.visibility = if (recorded && !isRecording) View.VISIBLE else View.GONE
        row.delete.visibility = if (recorded && !isRecording) View.VISIBLE else View.GONE
        row.play.text = if (isPlaying) "⏹" else "▶"
    }

    private fun refreshAll() { rows.values.forEach { refreshRow(it) } }

    // -------------------------------------------------------------- recording

    private fun onRecordTapped(key: String) {
        if (recordingKey == key) {           // second tap on the same row = stop & save
            stopRecording(save = true)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            pendingRecordKey = key
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        startRecording(key)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode != REQ_MIC) return
        val key = pendingRecordKey.also { pendingRecordKey = null } ?: return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startRecording(key)
        } else {
            Toast.makeText(this, "మైక్ అనుమతి కావాలి · Mic permission needed", Toast.LENGTH_LONG).show()
        }
    }

    private fun startRecording(key: String) {
        stopPlayback()
        stopRecording(save = true)           // finishing another row's take first
        tempFile.delete()

        val r = MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // String overload on purpose: the File overload is API 26+ but some
            // OEM builds misbehave with it — the String path is the safe API 27 route.
            r.setOutputFile(tempFile.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder failed to start", e)
            runCatching { r.release() }
            Toast.makeText(this, "రికార్డింగ్ మొదలవలేదు · Could not start recording", Toast.LENGTH_LONG).show()
            return
        }
        recorder = r
        recordingKey = key
        refreshAll()
    }

    /**
     * Stop the active recording. [save]=true moves the take into clips/ —
     * delete-first so an old clip with another extension can't shadow the new
     * one. stop() throws if nothing was captured yet (tapped stop instantly);
     * that take is simply discarded.
     */
    private fun stopRecording(save: Boolean) {
        val r = recorder ?: return
        val key = recordingKey
        recorder = null
        recordingKey = null

        var captured = false
        try {
            r.stop()
            captured = true
        } catch (e: Exception) {
            Log.w(TAG, "stop() with nothing captured — take discarded", e)
        }
        runCatching { r.release() }

        if (save && captured && key != null && tempFile.length() > 0) {
            ClipStore.delete(this, key)
            tempFile.copyTo(ClipStore.targetFile(this, key), overwrite = true)
            // The morning heartbeat rides on this clip: real voice = on.
            if (key == "goodmorning") Settings.setHeartbeatEnabled(this, true)
            Toast.makeText(this, "సేవ్ అయింది ✔ · Saved", Toast.LENGTH_SHORT).show()
        }
        tempFile.delete()
        refreshAll()
    }

    // --------------------------------------------------------------- playback

    private fun onPlayTapped(key: String) {
        if (playingKey == key) {             // tap again = stop listening
            stopPlayback()
            refreshAll()
            return
        }
        stopRecording(save = true)
        stopPlayback()
        val f = ClipStore.fileFor(this, key) ?: return

        val p = MediaPlayer()
        try {
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { stopPlayback(); refreshAll() }
            p.prepare()
            p.start()
        } catch (e: Exception) {
            Log.e(TAG, "Clip playback failed: ${f.name}", e)
            runCatching { p.release() }
            Toast.makeText(this, "ప్లే అవలేదు · Could not play", Toast.LENGTH_SHORT).show()
            return
        }
        player = p
        playingKey = key
        refreshAll()
    }

    private fun stopPlayback() {
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null
        playingKey = null
    }

    // ----------------------------------------------------------------- delete

    private fun confirmDelete(spec: ClipSpec) {
        AlertDialog.Builder(this)
            .setTitle(spec.label)
            .setMessage("ఈ రికార్డింగ్ తీసేయాలా? తీసేస్తే మామూలు గొంతు వినిపిస్తుంది.\nDelete this clip? The phone falls back to the robot voice.")
            .setPositiveButton("తీసేయండి · Delete") { _, _ ->
                if (playingKey == spec.key) stopPlayback()
                ClipStore.delete(this, spec.key)
                // No real "good morning" voice left -> heartbeat goes quiet again.
                if (spec.key == "goodmorning") Settings.setHeartbeatEnabled(this, false)
                refreshAll()
            }
            .setNegativeButton("వద్దు · Keep", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "RecorderActivity"
        private const val REQ_MIC = 41
    }
}
