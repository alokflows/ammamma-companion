# Ammamma Companion — Hands-on Build Plan (for an executor model)

**READ THIS WHOLE FILE FIRST. Do the steps IN ORDER. Do NOT invent anything not written here.**
After each PART, run the build command in PART 6 and fix errors before moving on.

## ⛔ STRICT RULES — READ AND OBEY. Breaking any of these is a failure.
1. **DO NOT MESS UP.** This is an old lady's only phone. A broken build or a silent voice is unacceptable. If you are unsure, STOP and leave a note — do not guess.
2. **BE PERFECT.** Every change must compile and behave exactly as described. No half-finished edits.
3. **DO NOT TOUCH WORKING CODE.** If a file/function is not named in a step, do not edit it. Do not "improve", reformat, rename, or reorder anything that already works.
4. **ONLY TOUCH WHAT IS NECESSARY.** Make the smallest change that satisfies the step. No drive-by refactors, no new dependencies, no new files beyond the ones listed.
5. **BE SURGICAL.** Match the exact `old` text and replace with the exact `new` text. Preserve indentation, imports, and Telugu strings byte-for-byte.
6. **CLEAN UP YOUR OWN MESS.** Delete any temp files, scratch scripts, half-written files, or debug logging you added. Leave the tree exactly as clean as you found it (only the intended changes remain). Run `git status` at the end — nothing unexpected should be there.
7. **VERIFY BEFORE MOVING ON.** Run `./gradlew assembleDebug -q && echo BUILD_OK` after each PART. Do not start the next PART until you see `BUILD_OK`.
8. **CHECK STATE FIRST.** Before doing a PART, look at the files — a PART may already be done (see PART 0). If it's already there and compiles, tick it and skip; do not redo it.

## HOW TO SEE WHERE THIS GOT
Run `git log --oneline -15` and read **PART 0** below. Everything under "ALREADY DONE" is finished and compiling — do not redo it. Start at the first PART that is NOT in PART 0. Verify the build is green (PART 6 command) before you begin.

Project root: `/Users/megha/Documents/repos/AmmammaCompanion`
Language: Kotlin. minSdk 26, targetSdk 27 (Android 8.1). No new libraries — use only the Android SDK.
Build env:
```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
ADB=~/Library/Android/sdk/platform-tools/adb
```

---

## PART 0 — WHAT IS ALREADY DONE (do NOT redo; just verify it compiles)

These files were already edited and the project **already builds**. Do not rewrite them. If you touch them, only make the small additions in PART 4/PART 5 below.

1. **`Announcer.kt`** — rewritten for VOICE CONSISTENCY (the #1 user complaint "sometimes it speaks, sometimes it doesn't"). Key changes already in place:
   - TTS engine is self-healing (re-created if null) and **never torn down by the service** anymore.
   - TTS now plays on `STREAM_MUSIC` (the stream we actually raise to max).
   - Volume save/restore is race-safe (`restoreVolumeIfIdle()` only restores when nothing is speaking).
   - `shutdown()` was REMOVED. Nothing should call it.
2. **`CompanionService.kt`** — `onDestroy()` now calls `announcer.stopSpeaking()` (NOT shutdown).
3. **`TalkActivity.kt` line ~52** — greeting fixed from romanized `"Cheppamma, emi kavali"` to Telugu script `"చెప్పమ్మా, ఏం కావాలి"`.
4. **`Settings.kt`** — added multi-key AI accounts: `data class AiAccount(key, model)`, `baseForKey()`, `providerLabel()`, `aiAccounts()`, `saveAiAccounts()`. Provider is detected from key prefix (`gsk_`=Groq, `sk-or-`=OpenRouter, `sk-`=OpenAI). Old `aiBaseUrl/aiModel/saveAiConfig/aiBaseUrlRaw` were REMOVED.
5. **`AiBrain.kt`** — rewritten: `ask()` and `assistant()` iterate multiple keys (fallback on failure), `fetchModels(apiKey)`, curated auto-pick (`PREFERRED` list, `llama-3.3-70b-versatile` first). Adds `User-Agent` header (some providers block the default Java UA).
6. **`SettingsActivity.kt` + `activity_settings.xml`** — AI section is now dynamic key rows (`keysContainer`) with a per-row **"Get models ▾"** button, an **"Add another API key"** button, and per-key model = **Auto (best)** or a chosen model.
7. **`CommandRouter.kt` (NEW) + `TalkActivity.kt`** — VOICE COMMANDS (PART 4) are DONE and compiling. `CommandRouter.resolve()` runs the AI in JSON-intent mode and returns a `Call`/`Launch`/`Say` action; `TalkActivity.handleUtterance()` executes it and speaks on every branch. Names-only privacy is enforced (numbers looked up on-device). **PART 4 below is already applied — skip it.**
8. **`TheftGuard.kt` (NEW) + `BatteryWatcher.kt` + `AndroidManifest.xml` + `SettingsActivity.kt` + `activity_settings.xml`** — THEFT FEATURE (PART 5) is DONE and compiling. On a charger event in Travel mode, `TheftGuard.onChargerEvent()` silently captures front+back photos (Camera2, no preview) to `filesDir/theft/` and toasts "📷 ఫోటో తీసారు · ✉️ సందేశం పంపారు"; the normal spoken charge line and the location SMS are unchanged. CAMERA permission added to manifest, the permission row, and the Save request set. **PART 5 below is already applied — skip it.**

**➡️ NEXT UNFINISHED WORK STARTS AT PART 6 (build + emulator smoke-test) and PART 7 (Sonnet sub-agents + final report). All code (PARTS 4 and 5) is written and compiling.**

**Verify PART 0 compiles before doing anything else:**
```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
cd /Users/megha/Documents/repos/AmmammaCompanion && ./gradlew assembleDebug -q && echo BUILD_OK
```
If it does not print `BUILD_OK`, fix the compile error before continuing. Do NOT start PART 4 until this is green.

---

## THE MODEL RECOMMENDATION (already validated with the real Groq key)

Tested live against Groq with the user's key. **Use `llama-3.3-70b-versatile`.**
- Warmest, most natural spoken Telugu of all candidates.
- Perfectly follows the JSON command format (call/open/chat).
- Sub-second (0.4–0.9s) on Groq, generous free tier, 70B so it genuinely "thinks" (not a cheap no-brain model).
- The auto-pick `PREFERRED` list in `AiBrain.kt` already puts it first, so leaving a key on **"Auto (best)"** selects it automatically on Groq.

The Groq key to preload for testing: `gsk_your_groq_api_key_here`
Groq base URL (auto-detected from the `gsk_` prefix): `https://api.groq.com/openai/v1`

---

## PART 4 — VOICE ASSISTANT COMMANDS (open app / call by name)  ✅ ALREADY DONE (see PART 0 #7)

> This part is already implemented and compiling. Verify `CommandRouter.kt` exists and `TalkActivity.handleUtterance` uses it, then move on to PART 5. The spec is kept below for reference only.

**Goal:** When Ammamma speaks, the AI decides: call a person, open an app, or just chat.
**PRIVACY RULE:** Only contact NAMES are sent to the AI. The phone NUMBER is looked up on the phone and never sent out.

### 4.1 — Create a NEW file `app/src/main/java/com/ammamma/companion/CommandRouter.kt`

Paste this file EXACTLY:

```kotlin
package com.ammamma.companion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings as AndroidSettings
import android.util.Log
import org.json.JSONObject

/**
 * Turns what Ammamma said into an ACTION. Runs the AI in "assistant" (JSON-intent)
 * mode, then resolves it ON THE PHONE: call a contact, open an app, or just chat.
 *
 * PRIVACY: only contact NAMES ever go to the AI. The number is looked up locally
 * and never leaves the phone.
 */
object CommandRouter {

    private const val TAG = "Ammamma"

    sealed class Action {
        data class Say(val spoken: String, val detail: String = "") : Action()
        data class Call(val number: String, val spoken: String) : Action()
        data class Launch(val intent: Intent, val spoken: String) : Action()
    }

    // Apps she might name -> package + web fallback + Telugu name.
    private data class KnownApp(val pkg: String, val web: String?, val telugu: String)
    private val KNOWN = mapOf(
        "youtube" to KnownApp("com.google.android.youtube", "https://m.youtube.com", "యూట్యూబ్"),
        "whatsapp" to KnownApp("com.whatsapp", "https://web.whatsapp.com", "వాట్సాప్"),
        "maps" to KnownApp("com.google.android.apps.maps", "https://maps.google.com", "మ్యాప్స్"),
        "chrome" to KnownApp("com.android.chrome", "https://www.google.com", "బ్రౌజర్"),
        "camera" to KnownApp("", null, "కెమెరా"),
        "phone" to KnownApp("", null, "ఫోన్"),
        "settings" to KnownApp("", null, "సెట్టింగ్స్")
    )

    /** BLOCKING (network inside) — call OFF the main thread. */
    fun resolve(context: Context, userText: String, extraContext: String?): Action {
        val contacts = Contacts.load(context)
        val contactNames = contacts.map { it.name }.filter { it.isNotBlank() }
        val appNames = KNOWN.keys.toList()

        val r = AiBrain.assistant(context, userText, contactNames, appNames, extraContext)
        if (!r.ok) return Action.Say(r.text, r.detail)

        val json = extractJson(r.text) ?: return Action.Say(r.text) // model just chatted plainly
        return when (json.optString("action")) {
            "call" -> resolveCall(context, contacts, json.optString("name"))
            "open" -> resolveOpen(context, json.optString("app"))
            else -> Action.Say(json.optString("say").ifBlank { r.text })
        }
    }

    private fun resolveCall(context: Context, contacts: List<Contact>, name: String): Action {
        if (name.isBlank()) return Action.Say("ఎవరికి ఫోన్ చేయాలో అర్థం కాలేదు")
        val hit = contacts.firstOrNull { it.name.equals(name, true) }
            ?: contacts.firstOrNull { it.name.contains(name, true) || name.contains(it.name, true) }
        val number = hit?.number?.takeIf { it.isNotBlank() } ?: lookupDeviceNumber(context, name)
        if (number.isNullOrBlank()) return Action.Say("$name నంబర్ లేదు, ఇంట్లో వాళ్ళను అడగండి")
        return Action.Call(number, "${hit?.name ?: name}కి ఫోన్ చేస్తున్నాను")
    }

    private fun resolveOpen(context: Context, app: String): Action {
        if (app.isBlank()) return Action.Say("ఏ యాప్ తెరవాలో అర్థం కాలేదు")
        val pm = context.packageManager
        val key = app.lowercase().trim()
        val known = KNOWN[key]

        // Built-in intents for camera / phone / settings.
        when (key) {
            "camera" -> return Action.Launch(
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "కెమెరా తెరుస్తున్నాను")
            "phone" -> return Action.Launch(
                Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "ఫోన్ తెరుస్తున్నాను")
            "settings" -> return Action.Launch(
                Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "సెట్టింగ్స్ తెరుస్తున్నాను")
        }

        // Known package installed?
        known?.pkg?.takeIf { it.isNotBlank() }?.let { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.let {
                return Action.Launch(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "${known.telugu} తెరుస్తున్నాను")
            }
        }
        // Match any installed app whose label contains the name.
        findInstalledByLabel(context, key)?.let {
            return Action.Launch(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "$app తెరుస్తున్నాను")
        }
        // Not installed but has a website -> open browser and TELL her.
        known?.web?.let {
            return Action.Launch(
                Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "${known.telugu} యాప్ లేదు, బ్రౌజర్‌లో తెరుస్తున్నాను")
        }
        return Action.Say("$app ఈ ఫోన్‌లో లేదు")
    }

    private fun findInstalledByLabel(context: Context, key: String): Intent? {
        val pm = context.packageManager
        return try {
            pm.getInstalledApplications(0).firstOrNull { info ->
                val label = pm.getApplicationLabel(info).toString().lowercase()
                label.contains(key) && pm.getLaunchIntentForPackage(info.packageName) != null
            }?.let { pm.getLaunchIntentForPackage(it.packageName) }
        } catch (e: Exception) { null }
    }

    /** Look up a phone number by contact display name — stays on the phone. */
    private fun lookupDeviceNumber(context: Context, name: String): String? {
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val sel = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            context.contentResolver.query(uri, cols, sel, arrayOf("%$name%"), null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "contact lookup failed", e); null
        }
    }

    /** Pull the first {...} block out of the model's reply and parse it. */
    private fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(text.substring(start, end + 1)) } catch (e: Exception) { null }
    }
}
```

### 4.2 — Edit `TalkActivity.kt`

**(a)** Add this import next to the other imports (near `import android.content.Intent`):
```kotlin
import android.net.Uri
```

**(b)** REPLACE the entire `handleUtterance` function. Find the function that currently starts with:
```kotlin
    /** She said (or typed) [text]; get a warm Telugu reply, show it and speak it. */
    private fun handleUtterance(text: String) {
```
…and replace that whole function (up to its closing brace before `private fun hideKeyboard()`) with:

```kotlin
    /** She said (or typed) [text]; route it to a command or a warm Telugu reply. */
    private fun handleUtterance(text: String) {
        status.text = "ఆలోచిస్తోంది…"   // thinking
        reply.text = text
        bg.execute {
            // Weather questions still get today's facts folded in for a warm answer.
            val weather = if (Weather.isWeatherQuestion(text)) Weather.factsForBrain() else null
            val action = CommandRouter.resolve(this, text, weather)
            main.post {
                val voice = Announcer.get(this)
                when (action) {
                    is CommandRouter.Action.Say -> {
                        status.text = ""
                        reply.text = action.spoken
                        voice.say(action.spoken)
                    }
                    is CommandRouter.Action.Call -> {
                        status.text = ""
                        reply.text = action.spoken
                        voice.say(action.spoken)
                        try {
                            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${action.number}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: SecurityException) {
                            voice.say("ఫోన్ చేయడానికి అనుమతి లేదు")
                        }
                    }
                    is CommandRouter.Action.Launch -> {
                        status.text = ""
                        reply.text = action.spoken
                        voice.say(action.spoken)
                        try { startActivity(action.intent) }
                        catch (e: Exception) { voice.say("అది తెరవడం కుదరలేదు") }
                    }
                }
            }
        }
    }
```

Nothing else in `TalkActivity.kt` changes. `CALL_PHONE`, `READ_CONTACTS` are already in the manifest.

---

## PART 5 — THEFT FEATURE (silent front+back photo + location SMS + toast)  ✅ ALREADY DONE (see PART 0 #8)

> This part is already implemented and compiling. Verify `TheftGuard.kt` exists, `BatteryWatcher` calls `TheftGuard.onChargerEvent(context)` in both charger branches, and the manifest has `CAMERA`. Then go to PART 6. Spec kept below for reference only.

**Behaviour required by the user:**
- Only when **Travel mode is ON** (the existing switch in Settings).
- On charger **plug OR unplug**: the phone behaves NORMALLY out loud — it still speaks "ఛార్జర్ పెట్టారు, ఇప్పుడు X శాతం" like always (so a thief hears a normal phone). **Do not add any spoken theft words.**
- SILENTLY in the background: capture ONE photo from the front camera and ONE from the back, save them on the phone, and (already implemented) SMS the GPS location to family.
- The ONLY visible sign is a small in-app **Toast**: "📷 ఫోటో తీసారు · ✉️ సందేశం పంపారు" (photo taken · message sent).

> Note for the user in the final report: SMS cannot carry an image, so photos are SAVED ON THE PHONE at `filesDir/theft/`. The SMS carries the location link. This is expected.

### 5.1 — Create a NEW file `app/src/main/java/com/ammamma/companion/TheftGuard.kt`

Paste EXACTLY:

```kotlin
package com.ammamma.companion

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The theft guard. When Travel mode is on and the charger is plugged in or out, this
 * SILENTLY grabs one front and one back photo and shows a small toast. The normal
 * spoken "charger connected, X%" line still happens (BatteryWatcher), so to a thief
 * the phone looks completely normal. The location SMS is sent by LocationReplyService.
 *
 * Camera2 can capture with NO preview by targeting only an ImageReader surface.
 * Everything is guarded + time-boxed so it can never hang the charger broadcast.
 * Best-effort: no camera / no permission -> it just logs and the SMS still goes out.
 */
object TheftGuard {

    private const val TAG = "Ammamma"
    private val main = Handler(Looper.getMainLooper())

    /** Call from BatteryWatcher on a charger event. Returns immediately (works on a thread). */
    fun onChargerEvent(context: Context) {
        val app = context.applicationContext
        Thread {
            val photos = captureBothCameras(app)
            main.post {
                val msg = if (photos.isNotEmpty()) "📷 ఫోటో తీసారు · ✉️ సందేశం పంపారు"
                          else "✉️ సందేశం పంపారు"
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun captureBothCameras(context: Context): List<File> {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Theft capture skipped: no CAMERA permission"); return emptyList()
        }
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val dir = File(context.filesDir, "theft").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val saved = mutableListOf<File>()
        for (facing in listOf(CameraCharacteristics.LENS_FACING_BACK, CameraCharacteristics.LENS_FACING_FRONT)) {
            val id = cameraIdFor(mgr, facing) ?: continue
            val name = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"
            val out = File(dir, "theft_${stamp}_$name.jpg")
            if (captureOne(context, mgr, id, out)) saved.add(out)
        }
        Log.i(TAG, "Theft capture saved ${saved.size} photo(s) in $dir")
        return saved
    }

    private fun cameraIdFor(mgr: CameraManager, facing: Int): String? = try {
        mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        }
    } catch (e: Exception) { null }

    @SuppressLint("MissingPermission")
    private fun captureOne(context: Context, mgr: CameraManager, id: String, out: File): Boolean {
        val thread = HandlerThread("theftcam").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        var ok = false
        var device: CameraDevice? = null
        val reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1)
        reader.setOnImageAvailableListener({ r ->
            try {
                r.acquireLatestImage()?.use { img ->
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                    FileOutputStream(out).use { it.write(bytes) }
                    ok = true
                }
            } catch (e: Exception) { Log.e(TAG, "theft save fail", e) }
            finally { done.countDown() }
        }, handler)

        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    try {
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        req.addTarget(reader.surface)
                        cam.createCaptureSession(listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    try { s.capture(req.build(), null, handler) }
                                    catch (e: Exception) { Log.e(TAG, "capture fail", e); done.countDown() }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) { done.countDown() }
                            }, handler)
                    } catch (e: Exception) { Log.e(TAG, "session fail", e); done.countDown() }
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); done.countDown() }
                override fun onError(cam: CameraDevice, e: Int) { cam.close(); done.countDown() }
            }, handler)
            done.await(6, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera fail", e)
        } finally {
            try { device?.close() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            thread.quitSafely()
        }
        return ok
    }
}
```

### 5.2 — Edit `BatteryWatcher.kt` (two small additions)

In `onReceive`, find the POWER_CONNECTED block. It contains:
```kotlin
                if (Settings.travelModeEnabled(context)) {
                    LocationReplyService.startTravelPing(context, pluggedIn = true)
                }
```
Change it to:
```kotlin
                if (Settings.travelModeEnabled(context)) {
                    LocationReplyService.startTravelPing(context, pluggedIn = true)
                    TheftGuard.onChargerEvent(context)   // silent front+back photo + toast
                }
```

Then find the POWER_DISCONNECTED block:
```kotlin
                if (Settings.travelModeEnabled(context)) {
                    LocationReplyService.startTravelPing(context, pluggedIn = false)
                }
```
Change it to:
```kotlin
                if (Settings.travelModeEnabled(context)) {
                    LocationReplyService.startTravelPing(context, pluggedIn = false)
                    TheftGuard.onChargerEvent(context)   // silent front+back photo + toast
                }
```

### 5.3 — Edit `app/src/main/AndroidManifest.xml`

Find the line:
```xml
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
```
Add these two lines right AFTER it:
```xml
    <!-- Theft guard: silently photograph whoever handles the phone in Travel mode. -->
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />
```

### 5.4 — Edit `SettingsActivity.kt` (ask for camera permission + show its row)

**(a)** In `permissionItems()`, find the microphone `PermItem(...)` entry. Add a new entry right after it:
```kotlin
        PermItem(
            "కెమెరా · Camera",
            { granted(Manifest.permission.CAMERA) },
            { requestGroup(Manifest.permission.CAMERA) }
        ),
```

**(b)** In `save()`, find:
```kotlin
        val needed = listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
```
Add `Manifest.permission.CAMERA,` to that list:
```kotlin
        val needed = listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
```

### 5.5 — (Optional) Update the Travel-mode help text in `activity_settings.xml`

Find the Travel-mode description text ("When ON: every time the charger is plugged in or out...") and you may append: " It also silently takes a front and back photo (saved on the phone) and shows a small 'photo taken' note." This is cosmetic; skip if unsure.

---

## PART 6 — BUILD, INSTALL, SMOKE-TEST ON THE EMULATOR

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
ADB=~/Library/Android/sdk/platform-tools/adb
cd /Users/megha/Documents/repos/AmmammaCompanion

# 1. Build
./gradlew assembleDebug -q && echo BUILD_OK

# 2. Emulator must be running (boot if needed, wait for it):
~/Library/Android/sdk/emulator/emulator -avd ammamma_oppo -no-snapshot-save >/tmp/emu.log 2>&1 &
$ADB wait-for-device
# wait until this prints "1":
$ADB shell getprop sys.boot_completed

# 3. Install
$ADB install -r app/build/outputs/apk/debug/app-debug.apk

# 4. Preload the Groq key + Travel mode via SharedPreferences, then launch.
#    (ai_accounts is a JSON array; model "" means Auto = llama-3.3-70b-versatile.)
$ADB shell "run-as com.ammamma.companion sh -c 'mkdir -p /data/data/com.ammamma.companion/shared_prefs'"
$ADB shell am start -n com.ammamma.companion/.MainActivity
# Open Settings in the app, paste the key, tap Get models ▾ (should show llama-3.3-70b-versatile
# at top), leave it on Auto, turn Travel mode ON, Save. OR set prefs directly with run-as.

# 5. Grant runtime permissions for headless testing:
for P in CALL_PHONE READ_CONTACTS RECORD_AUDIO CAMERA SEND_SMS RECEIVE_SMS ACCESS_FINE_LOCATION READ_PHONE_STATE READ_CALL_LOG; do
  $ADB shell pm grant com.ammamma.companion android.permission.$P; done

# 6. Test the theft trigger (charger events) + location + camera:
$ADB emu geo fix 78.60 18.20            # set a GPS location
$ADB shell am start -n com.ammamma.companion/.SettingsActivity   # ensure Travel mode ON + key saved
$ADB shell dumpsys battery unplug        # simulate unplug -> should speak + toast + save photo
$ADB shell dumpsys battery set ac 1      # simulate plug -> same
$ADB shell dumpsys battery reset
# Check photos were saved:
$ADB shell run-as com.ammamma.companion ls -l files/theft/
# Check the app logs for the flow:
$ADB logcat -d -s Ammamma | tail -40
```

**Pass criteria:**
- `BUILD_OK` prints.
- Logcat shows `AI reply (llama-3.3-70b-versatile): ...` in Telugu when you use Talk.
- On a charger event with Travel mode ON: logcat shows `Theft capture saved N photo(s)` and `Location SMS ...`; a toast appears. (On the emulator the camera may be emulated or absent — if `files/theft/` is empty but the SMS log is present, that is acceptable; note it.)
- Voice consistency: open the app several times; it must greet EVERY time (`नमస्ते...`/`చెప్పమ్మా...`). Open Talk and send a typed message twice in a row — it must speak BOTH times.

---

## PART 7 — SONNET SUB-AGENT TESTS + FINAL REPORT

The user explicitly wants TWO Sonnet sub-agents run against the RUNNING emulator (the app installed + Groq key set). Spawn them with the Agent tool, `subagent_type: "general-purpose"`, `model: "sonnet"`.

### 7.1 — "Grandma" agent (consistency + usability)
Prompt to give it (paste verbatim):
> You are role-playing **Ammamma**, a 70-year-old Telugu grandmother who CANNOT read and only speaks Telugu, testing the AmmammaCompanion app on the already-running Android emulator (`~/Library/Android/sdk/platform-tools/adb`). Do NOT build; the app is installed. Your job is to judge **consistency and reliability of the VOICE**, which is the owner's top concern.
> Steps: (1) `adb shell am force-stop com.ammamma.companion` then `adb shell am start -n com.ammamma.companion/.MainActivity`; confirm via `adb logcat -d -s Ammamma` that it SPOKE a greeting. Repeat this open/close **5 times** and report whether it spoke EVERY time (it must). (2) Open Talk (`adb shell am start -n com.ammamma.companion/.TalkActivity`), inject typed input twice: `adb shell input text "namaste"` then tap Send (dump the UI with `adb shell uiautomator dump` to find the button, or use `adb shell input tap`). Confirm it speaks a warm Telugu reply BOTH times. (3) Try a command turn: type a Telugu request to call a contact and to open youtube; confirm logcat shows the right action. Report every inconsistency (a screen that stayed silent, a mangled/romanized-sounding line, a dropped reply) with the exact logcat lines. Be harsh about consistency.

### 7.2 — "Thief" agent (theft feature)
Prompt to give it (paste verbatim):
> You are testing the THEFT feature of AmmammaCompanion on the running Android emulator. The app is installed; Travel mode must be ON and the Groq key set (open `.SettingsActivity` and verify, or set prefs via `run-as`). Simulate a thief moving the phone onto/off a charger and verify the SILENT capture: run `adb emu geo fix 78.6 18.2`, then `adb shell dumpsys battery unplug`, then `adb shell dumpsys battery set ac 1`, then `adb shell dumpsys battery reset`. After each event: (1) confirm `adb logcat -d -s Ammamma` shows `Theft capture saved N photo(s)` and a location-SMS log; (2) confirm a Toast appeared (check logcat / uiautomator); (3) confirm NO spoken THEFT words were added (only the normal "charger connected, X%" line is allowed); (4) `adb shell run-as com.ammamma.companion ls -l files/theft/` to confirm photo files exist. Report exactly what fired and what did not. If the emulator has no camera and `files/theft/` is empty, say so explicitly but confirm the SMS + toast path still ran.

### 7.3 — Final report to the user
Write a short message covering:
1. **Model recommendation:** `llama-3.3-70b-versatile` (why: warmest Telugu, perfect command JSON, sub-second, free tier, 70B = smart). Leave keys on "Auto (best)".
2. **What changed:** voice consistency fixes (self-healing engine, no more service teardown, media-stream volume, Telugu-script greeting); clean multi-key AI settings with per-key Get-models + Auto; voice commands (open app / call by name, names-only privacy); theft guard (silent front+back photo + location SMS + toast).
3. **Sub-agent findings** (from 7.1 and 7.2).
4. **Real-world test steps for Alok on the actual Oppo phone** (see PART 8).

---

## PART 8 — REAL-WORLD TEST STEPS FOR ALOK (put these in the final report)

1. Install the new APK over Wi-Fi (Telegram Saved Messages → tap the APK → allow install).
2. Open the app once. Open **Settings** (gear). In **Talk AI**: paste the Groq key `gsk_...`; it should show "**Groq · Auto (best model)**". Tap **Get models ▾** → the top item should be `llama-3.3-70b-versatile`; leave it on **✨ Auto**. (Optional: tap **Add another API key** and paste a second free key as backup.) Tap **Test AI** → expect "✅ AI works" with a Telugu line.
3. **Voice consistency check:** close and reopen the app 4–5 times. It must greet in Telugu EVERY time. Open **Talk**, tap the mic, say a sentence — it must reply out loud every time.
4. **Commands:** in Talk, say (Telugu) "call <a saved contact name>" → it should say "…కి ఫోన్ చేస్తున్నాను" and dial. Say "యూట్యూబ్ తెరువు" → opens YouTube (or the browser if not installed, telling her).
5. **Theft/Travel mode:** in Settings turn **Travel mode ON**, make sure family numbers are filled and Location + Camera permissions are green. Plug the charger in and out: the phone should speak the normal charge line, family should receive a location SMS, and a small "photo taken · message sent" toast should flash. Photos are saved on the phone under the app's `files/theft/` folder (retrieve via a file manager or `run-as`).
6. If anything is silent or inconsistent, capture `adb logcat -s Ammamma` and report.

---

## GUARDRAILS (do not violate)
- Do NOT call `Announcer.shutdown()` — it no longer exists.
- Do NOT send phone NUMBERS to the AI — only contact names.
- Do NOT add spoken words to the theft path — it must look/behave like a normal charge event; the only feedback is the toast.
- Keep all spoken strings in **Telugu script**, never romanized Latin (romanized text is the cause of the "sounds bad" inconsistency).
- No new third-party libraries. SDK only.
- Run `./gradlew assembleDebug -q` after PART 4 and after PART 5; fix errors before proceeding.
```
