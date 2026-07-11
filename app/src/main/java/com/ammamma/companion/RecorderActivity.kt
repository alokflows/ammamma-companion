package com.ammamma.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ammamma.companion.ClipCatalog.ClipSpec
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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

    // ClipSpec itself lives in ClipCatalog now (see the import above) — the
    // single source of truth for every recordable announcement.

    /** Live view references for one row so it can be refreshed after changes.
     *  [hintText] is the row's normal "చెప్పండి: …" line — saved so a transient
     *  "importing…"/"downloading…" message can be put back afterwards. */
    private data class Row(
        val spec: ClipSpec,
        val root: View,
        val record: Button,
        val play: Button,
        val delete: Button,
        val importBtn: Button,
        val hint: TextView,
        val hintText: String
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

    // ---- from-file / from-Drive import state -------------------------------
    // Which row's document-picker result we're waiting for (survives the trip
    // out to the system file picker and back).
    private var pendingImportKey: String? = null
    // At most one import runs at a time (surgical — no queue): the key it's
    // busy for, and what its row's hint line should say meanwhile.
    private var busyKey: String? = null
    private var busyLabel: String = ""
    // Background copy/download work posts its result back here.
    private val main = Handler(Looper.getMainLooper())

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

    /** Zero hardcoded rows here on purpose — every section and every spec
     *  comes from ClipCatalog, the single source of truth. Add a new
     *  announcement there and it shows up here automatically, no second
     *  edit needed. */
    private fun buildCatalog(container: LinearLayout) {
        ClipCatalog.sections(this).forEach { section ->
            addSection(container, section.title, section.helper)
            section.specs.forEach { spec -> addRow(container, spec) }
        }
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
        val hintText = "చెప్పండి: “${spec.hint}”"
        val hintView = v.findViewById<TextView>(R.id.clipHint).apply { text = hintText }

        val row = Row(
            spec, v,
            v.findViewById(R.id.btnRecord),
            v.findViewById(R.id.btnPlay),
            v.findViewById(R.id.btnDelete),
            v.findViewById(R.id.btnImport),
            hintView,
            hintText
        )
        row.record.setOnClickListener { onRecordTapped(spec.key) }
        row.play.setOnClickListener { onPlayTapped(spec.key) }
        row.delete.setOnClickListener { confirmDelete(spec) }
        row.importBtn.setOnClickListener { onImportTapped(spec.key) }

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
        val isBusy = busyKey == row.spec.key

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

        // While an import/download for THIS row is in flight, freeze every
        // button on it (a second tap mid-copy could race the validated-replace)
        // and show what's happening instead of the usual "say this" hint.
        row.record.isEnabled = !isBusy
        row.play.isEnabled = !isBusy
        row.delete.isEnabled = !isBusy
        row.importBtn.isEnabled = !isBusy
        row.hint.text = if (isBusy) busyLabel else row.hintText
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

    // ------------------------------------------------------------------ import
    // "Failure is not an option": every import (file or Drive) lands in a
    // cacheDir staging file, gets PROVEN playable with a real MediaPlayer
    // prepare(), and only THEN replaces the clip via ClipStore.commitImport —
    // which deletes every old extension first so nothing can shadow the new
    // file. Any failure at any step deletes the staging file and leaves
    // whatever clip already existed completely untouched.

    private fun onImportTapped(key: String) {
        if (busyKey != null) {
            Toast.makeText(this, "ఒక దిగుమతి పూర్తయ్యే వరకు ఆగండి · Please wait for the current import to finish", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("ఎక్కడి నుండి? · Import from")
            .setItems(arrayOf(
                "ఫైల్ నుండి ఎంచుకోండి · Choose a file on the phone",
                "Drive లింక్ నుండి · Paste a Google Drive link"
            )) { _, which ->
                if (which == 0) pickFile(key) else showDriveDialog(key)
            }
            .setNegativeButton("రద్దు · Cancel", null)
            .show()
    }

    /** ACTION_OPEN_DOCUMENT, filtered LENIENTLY: WhatsApp voice notes and some
     *  file managers report odd/video mime types for what is really just
     *  audio, so video/mp4 is accepted alongside the audio mime type rather
     *  than rejecting a perfectly good clip on a technicality. */
    private fun pickFile(key: String) {
        pendingImportKey = key
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/mp4"))
        }
        try {
            startActivityForResult(intent, REQ_FILE)
        } catch (e: Exception) {
            Log.e(TAG, "No file picker available", e)
            pendingImportKey = null
            Toast.makeText(this, "ఫైల్ ఎంపిక తెరవలేదు · Could not open the file picker", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE) return
        val key = pendingImportKey.also { pendingImportKey = null } ?: return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return   // user cancelled — nothing to touch
        runImport(key, "దిగుమతి అవుతోంది… · Importing…") { importDocument(key, uri) }
    }

    /** Dialog with a single paste field, accepting any common Drive URL shape
     *  (…/file/d/<id>/…, …?id=<id>) or a bare file id. */
    private fun showDriveDialog(key: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val hintLine = TextView(this).apply {
            text = "షేర్ చేసేటప్పుడు 'Anyone with the link' పెట్టండి · Share the file as \"Anyone with the link\" first"
            setTextColor(0xFF8A7361.toInt())
            textSize = 13f
        }
        val linkEt = EditText(this).apply {
            hint = "Drive లింక్ లేదా ఐడీ · Drive link or id"
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
        }
        box.addView(hintLine)
        box.addView(linkEt)

        AlertDialog.Builder(this)
            .setTitle("Drive నుండి దిగుమతి · Import from Drive")
            .setView(box)
            .setPositiveButton("డౌన్‌లోడ్ · Download") { _, _ ->
                val id = extractDriveId(linkEt.text.toString())
                if (id == null) {
                    val msg = "సరైన Drive లింక్ కాదు · That doesn't look like a Drive link"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    Announcer.get(this).say(msg)
                } else {
                    runImport(key, "డౌన్‌లోడ్ అవుతోంది… · Downloading…") { downloadFromDrive(key, id) }
                }
            }
            .setNegativeButton("రద్దు · Cancel", null)
            .show()
    }

    /** Pulls a Drive file id out of the shapes people actually paste:
     *  .../file/d/<id>/..., ...?id=<id>, or the bare id with nothing else. */
    private fun extractDriveId(raw: String): String? {
        val s = raw.trim()
        Regex("""/file/d/([\w-]+)""").find(s)?.let { return it.groupValues[1] }
        Regex("""[?&]id=([\w-]+)""").find(s)?.let { return it.groupValues[1] }
        // A Drive file id is a long token with no slashes/spaces — long enough
        // that we won't mistake a stray short word for one.
        if (s.matches(Regex("""[\w-]{15,}"""))) return s
        return null
    }

    /** Runs [work] (blocking — file copy or network download + validation) off
     *  the main thread, freezing this row meanwhile, and finishes on the UI
     *  thread. Only one import runs at a time — see [busyKey]. */
    private fun runImport(key: String, label: String, work: () -> String?) {
        stopRecording(save = true)   // finish/save whatever else was mid-flight first
        stopPlayback()
        busyKey = key
        busyLabel = label
        refreshAll()
        Thread {
            val err = work()
            main.post { finishImport(key, err) }
        }.apply { isDaemon = true; name = "clip-import" }.start()
    }

    /** [err] null = success: same "just installed" ritual as a fresh recording
     *  (heartbeat opt-in on "goodmorning", then auto-play so she/Alok hear it
     *  immediately and the row paints green). Non-null = show AND speak it —
     *  a silent failure on a screen she can't read is the one thing to avoid. */
    private fun finishImport(key: String, err: String?) {
        busyKey = null
        if (isFinishing) return   // screen is gone; the file is already committed/discarded above
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            Announcer.get(this).say(err)
            refreshAll()
            return
        }
        if (key == "goodmorning") Settings.setHeartbeatEnabled(this, true)
        Toast.makeText(this, "దిగుమతి అయింది ✔ · Imported", Toast.LENGTH_SHORT).show()
        onPlayTapped(key)   // plays it once + repaints the row, exactly like a fresh recording
    }

    /**
     * BLOCKING — background thread only. Copies the picked document into a
     * staging file, proves it's real playable audio, then commits it. Returns
     * null on success or a short Telugu+English line to show/speak on failure.
     */
    private fun importDocument(key: String, uri: Uri): String? {
        val staged = ClipStore.stagingFile(this, key)
        staged.delete()
        return try {
            val ins = contentResolver.openInputStream(uri)
                ?: return "ఫైల్ తెరవలేదు · Could not open the file"
            val ext = extensionForDocument(uri)
            ins.use { input -> staged.outputStream().use { out -> input.copyTo(out) } }
            if (staged.length() == 0L) {
                staged.delete()
                return "ఖాళీ ఫైల్ · The file was empty"
            }
            if (!validateAudio(staged)) {
                staged.delete()
                return "ఇది సరైన ఆడియో కాదు · That file isn't playable audio"
            }
            ClipStore.commitImport(this, key, staged, ext)
            null
        } catch (e: Exception) {
            Log.e(TAG, "File import failed for $key", e)
            staged.delete()
            "దిగుమతి విఫలమైంది · Could not import that file"
        }
    }

    /**
     * BLOCKING — background thread only. Downloads
     * https://drive.google.com/uc?export=download&id=<id> and validates it
     * exactly like a local file. Drive serves an HTML page (not the file)
     * when it isn't shared publicly — that is treated as a specific, actionable
     * failure rather than a generic one.
     */
    private fun downloadFromDrive(key: String, fileId: String): String? {
        val staged = ClipStore.stagingFile(this, key)
        staged.delete()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("https://drive.google.com/uc?export=download&id=$fileId")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return "డౌన్‌లోడ్ కాలేదు (HTTP $code) · Could not download the file"
            }
            val contentType = conn.contentType?.lowercase() ?: ""
            if (contentType.startsWith("text/html")) {
                // Drive hands back an HTML page instead of bytes when the link
                // isn't public — the fix is the sharing setting, not a retry.
                return "Drive link ki 'Anyone with the link' access pettandi"
            }
            val ext = extensionForDrive(conn)
            conn.inputStream.use { ins -> staged.outputStream().use { out -> ins.copyTo(out) } }
            if (staged.length() == 0L) {
                staged.delete()
                return "ఖాళీ ఫైల్ వచ్చింది · The download was empty"
            }
            if (!validateAudio(staged)) {
                staged.delete()
                return "ఇది సరైన ఆడియో కాదు · That link isn't a playable audio file"
            }
            ClipStore.commitImport(this, key, staged, ext)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Drive download failed for $key", e)
            staged.delete()
            "డౌన్‌లోడ్ విఫలమైంది · Could not download from Drive"
        } finally {
            conn?.disconnect()
        }
    }

    /** The failure-is-not-an-option check itself: a real MediaPlayer prepare()
     *  on the staged file, plus a floor on duration so a 0-byte-ish/corrupt
     *  capture that somehow still "prepares" can't pass as a clip. */
    private fun validateAudio(f: File): Boolean {
        val p = MediaPlayer()
        return try {
            p.setDataSource(f.absolutePath)
            p.prepare()
            p.duration > 300
        } catch (e: Exception) {
            Log.w(TAG, "Import validation failed for ${f.name}", e)
            false
        } finally {
            runCatching { p.release() }
        }
    }

    /** Prefer the picked document's own filename (SAF usually reports one);
     *  fall back to mapping its MIME type. This only decides the STORED
     *  filename — MediaPlayer plays by sniffing content, never by extension. */
    private fun extensionForDocument(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                extensionFromName(c.getString(0))?.let { return it }
            }
        }
        return extensionForMime(contentResolver.getType(uri))
    }

    /** Same idea for a Drive download: try the Content-Disposition filename
     *  Drive sends for small/public files, else fall back to Content-Type. */
    private fun extensionForDrive(conn: HttpURLConnection): String {
        val disposition = conn.getHeaderField("Content-Disposition")
        val name = disposition?.let { Regex("""filename="?([^";]+)"?""").find(it)?.groupValues?.get(1) }
        extensionFromName(name)?.let { return it }
        return extensionForMime(conn.contentType?.substringBefore(';'))
    }

    private fun extensionFromName(name: String?): String? {
        val ext = name?.substringAfterLast('.', "")?.trim()?.lowercase()
        return ext?.takeIf { it.isNotBlank() && it.length in 2..4 }
    }

    private fun extensionForMime(mime: String?): String = when (mime?.trim()?.lowercase()) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        else -> "m4a"   // covers audio/mp4, audio/x-m4a, video/mp4, and any unknown type
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
        private const val REQ_FILE = 42
    }
}
