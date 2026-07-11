# Ammamma's Phone Partner — HANDOFF / SINGLE SOURCE OF TRUTH

> Living document. If you are a new session (or context was summarized), READ THIS FIRST.
> It lets anyone continue the build line by line without losing anything.
> Keep it updated as work progresses. North star: **"Will this make grandma love her phone?"**
> Reliability > cleverness. Boring, readable Kotlin so a beginner can learn from it.

---

## 1. What this is
Offline-first Android app that turns an old Oppo into a talking companion for an
elderly, Telugu-only grandmother (Ammamma) in Huzurabad, Telangana. She uses the
phone only for calls, can't reliably read screen text → audio-first, huge photo
buttons, warmth over gadgetry. Internet only ENRICHES (Gemini); it is never required.

Full product brief: `/Users/megha/Documents/Grandma Companian/GRANDMA_PHONE_PARTNER_BRIEF (1).md`

## 2. Target device (emulate this exactly)
Oppo CPH1853 (A3s), ColorOS 5.2.1, **Android 8.1.0 (API 27)**, Snapdragon 450,
**2 GB RAM**, 16 GB storage (nearly full). ColorOS kills background apps hard.

**Cable reality:** her only USB cable is charge-only (no data). Android 8 can't
enable wireless ADB without a one-time USB. So: all dev/test is on the EMULATOR;
final install to her phone is over Wi-Fi (Telegram → Saved Messages → tap the APK →
allow "install unknown apps"). App is ~0.8 MB, so this is trivial.

## 3. Dev environment (macOS, Apple Silicon / arm64)
```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17     # JDK 17 (keg-only, not on PATH by default)
ADB=~/Library/Android/sdk/platform-tools/adb
SDK=~/Library/Android/sdk
```
- Android SDK at `~/Library/Android/sdk`. Installed: platform android-27, system image
  `system-images;android-27;google_apis;arm64-v8a`.
- Emulator AVD: **`ammamma_oppo`** (API 27, tuned to 2 GB RAM / 720x1520 to mirror device).
- Gradle: system gradle is 9.6.1 (too new for AGP) → project uses the **wrapper pinned to 8.7**
  (`./gradlew`). AGP 8.5.2, Kotlin 1.9.24.

### Build / run / test commands
```
# Boot the emulator (first boot ~15s)
$SDK/emulator/emulator -avd ammamma_oppo -no-snapshot -gpu auto -no-boot-anim &
$ADB wait-for-device

# Build + install
cd /Users/megha/AmmammaCompanion
export JAVA_HOME=/opt/homebrew/opt/openjdk@17; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug --no-daemon
$ADB -e install -r app/build/outputs/apk/debug/app-debug.apk

# Launch + logs + screenshot
$ADB -e shell am start -n com.ammamma.companion/.MainActivity
$ADB -e logcat -d -s "Ammamma"                 # our logs use tag "Ammamma"
$ADB -e exec-out screencap -p > /tmp/shot.png

# Simulate battery / charging (no second phone needed)
$ADB -e shell dumpsys battery unplug
$ADB -e shell dumpsys battery set level 20      # -> low-battery red card
$ADB -e shell dumpsys battery set ac 1; $ADB -e shell dumpsys battery set status 2   # charging
$ADB -e shell dumpsys battery reset             # restore

# Simulate find-my-phone SMS
$ADB -e shell pm grant com.ammamma.companion android.permission.RECEIVE_SMS
$ADB -e emu sms send 15551234567 "please FINDME now"
```
Note: activities that are `exported="false"` (Settings, Alert, Charging, FindPhone)
CANNOT be launched with `adb am start` from shell (Permission Denial — this is
correct). Launch them via the in-app path (tap the gear, trigger the event).

## 4. Project structure & what each file does
Package `com.ammamma.companion` at `/Users/megha/AmmammaCompanion/app/src/main`.

| File | Role |
|---|---|
| `MainActivity.kt` | Home. Live clock, builds photo-dial grid from `Contacts`, one-tap ACTION_CALL, opens Talk + Settings. Starts the service. |
| `CompanionService.kt` | Foreground service = heartbeat that survives ColorOS. Owns the Announcer + registers BatteryWatcher. START_STICKY. |
| `Announcer.kt` | The single voice. **Clip-first** (`filesDir/clips/<eventKey>.*`) then TTS (Telugu) fallback. Raises media volume then restores. Singleton via `Announcer.get(ctx)`. |
| `BatteryWatcher.kt` | Dynamic receiver (battery + charger). Low-battery warnings at 20%/10% (red `AlertActivity`), launches `ChargingActivity` on plug-in, feeds `ChargeState`. |
| `ChargeState.kt` | Estimates time-to-full ourselves (API 27 lacks computeChargeTimeRemaining). Samples % over time. |
| `ChargingActivity.kt` | Live charging screen: big %, battery health, our estimated time-to-full, spoken aloud. Updates live, closes on unplug. |
| `AlertActivity.kt` | Giant full-screen dismiss card (low battery red / battery-full green). Full-screen Activity (NOT overlay) → no ColorOS overlay permission needed. |
| `Contact.kt` | `Contact` data + `Contacts.sample` list (name/english/number/ringColor). Placeholder people until family fills real ones. |
| `TalkActivity.kt` | Voice companion screen. SHELL — Gemini wires in here next. |
| `SettingsActivity.kt` + `Settings.kt` | Family-only settings (gear on home). SharedPreferences: find-phone code word, allowed family numbers, **Gemini API key**. No rebuild to change these. |
| `FindPhoneReceiver.kt` | Manifest SMS receiver. Code word (+ optional family-number filter) → `FindPhoneActivity`. Guarded by BROADCAST_SMS permission. |
| `FindPhoneActivity.kt` | "ఇక్కడ ఉన్నా!" — loud alarm on STREAM_ALARM at max (sounds on silent) + vibrate, big Stop button. |
| `res/layout/*`, `res/drawable/*` | Warm cream (#FBF3E8) / brown (#402A1C) theme, green (#1E8E3E) = call. Big text, rounded cards, per-person ring colors. |

## 5a. v0.4 (2026-07-07) — the "day before leaving" hardening. EMULATOR-VERIFIED ✅
Alok's real-device bug report → four parallel fix branches, merged + tested end-to-end:
- **Caller voice over the ringtone**: STREAM_RING/NOTIFICATION ducked to ~25% while the name
  repeats on max media; restored on answer/end. VERIFIED (log: "Ring ducked…", restore OK).
- **Reads the PHONE's contact book**: app contacts → ContactsContract.PhoneLookup → unknown.
  VERIFIED: device-only contact "Ravi" announced by name. Needs READ_CONTACTS (in first-run set).
- **Find-my-phone made un-strandable**: alarm sound/vibration moved INTO CompanionService
  (never an invisible activity again). Four stop paths: giant ఆపు button, full-screen/heads-up
  notification ("Tap to stop"), opening the app auto-shows the stop screen (MainActivity.onResume),
  3-minute auto-stop. Old alarm volume restored. ALL VERIFIED on emulator incl. escape hatch.
- **Persistence (3 layers)**: BootReceiver (BOOT_COMPLETED + MY_PACKAGE_REPLACED — VERIFIED
  post-reboot with zero touch), onTaskRemoved resurrection alarm (+1.5s), 15-min watchdog
  setInexactRepeating (VERIFIED armed in dumpsys alarm). Recents-swipe: service unaffected.
- **Battery fully customizable** (Settings): low % (def 20), urgent % (def 10), remind-every-N-min
  (def 5), charged-enough % (def 100). Reminder-stop now follows the configured threshold.
  VERIFIED: red card + spoken "ఛార్జ్ 18 శాతం…" at 18%, full alert at 100%.
- **Demo section in Settings**: caller / low battery / battery full / charging screen / find-phone.
- **Family numbers UI**: dynamic rows + "＋ ADD NUMBER" (same comma-separated pref underneath).
- **Permissions once**: first-run storm runs a single time (setup_flags pref), then never re-asks;
  battery-optimization + overlay asks sequenced once. Location-off → spoken warning + red banner.
- **Warmer TTS**: best offline te-IN voice by quality, rate 0.92, accessibility audio attributes.
- **SETUP_PHONE.md**: Alok's one-hour install checklist (ColorOS never-kill steps + 5 leave-tests).
Signed release: `app/build/outputs/apk/release/app-release.apk` (~660 KB).

## 5. Status — built & EMULATOR-TESTED ✅
- [x] Dev env + API-27 arm64 emulator mirroring the device
- [x] Foreground service (verified `isForeground=true`)
- [x] Low-battery partner: red card at 20%/10%, giant dismiss (device-tested)
- [x] Charging screen: %, battery health, self-computed time-to-full, spoken
- [x] **Caller announcement** (`CallReceiver`, OFFLINE): phone rings → looks up the number in
      Contacts → speaks "<name> ఫోన్ చేస్తున్నారు" (Telugu, via Announcer/TTS); unknown →
      "ఎవరో ఫోన్ చేస్తున్నారు". VERIFIED on emulator (known + unknown, spoken via eSpeak).
      Needs READ_PHONE_STATE (requested at launch). THE feature she needs most.
- [x] Photo-dial Home: live clock + 6 face cards, **verified one-tap ACTION_CALL** places a call;
      no-number cards dimmed with a permanent "నంబర్ లేదు" label; long-press to edit.
- [x] Announcer (clip-first, TTS fallback, volume raise/restore)
- [x] Settings screen (code word / family numbers / OpenRouter AI key) — gear on home
- [x] Find-my-phone by SMS (alarm + vibrate) — tested via emulator SMS
- [x] **Grandpa finder** (`LocationReplyService`): find-phone SMS → phone also texts its GPS
      location back to the sender as a `maps.google.com/?q=lat,lon` link. Chain verified on
      emulator (correct link built + SMS sent to sender). NOTE: emulator returns its default
      Googleplex coord for last-known GPS (`geo fix` doesn't reliably update last-known); a real
      device returns the real location. Possible refinement: prefer a fresh fix if last-known is stale.
- [x] **Talk companion brain** (`AiBrain` + `TalkActivity`): mic → STT → OpenRouter → warm Telugu
      reply → TTS. AI round-trip VERIFIED in-app on emulator (got warm Telugu reply from Gemma-4).
      STT (SpeechRecognizer te-IN) + TTS need the real phone (emulator has no speech engine).
- [x] Home-screen design published: https://claude.ai/code/artifact/5bf74497-37bf-4525-b712-2ec990e03669
- [x] **App icon**: adaptive icon, small white Apple-style heart on a rose/pink gradient.
- [x] **First-run permissions**: MainActivity requests all 6 runtime perms at once + battery-
      optimization exemption (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) for persistence.
- [x] **Caller demo** button in Settings — speaks "<name> ఫోన్ చేస్తున్నారు" on demand (offline).
- [x] **Weather (Telugu), offline-key-free** (`Weather.kt`): when she asks about rain/heat/cold
      (`isWeatherQuestion` — Telugu + English keywords), `TalkActivity` fetches live Huzurabad
      forecast from **Open-Meteo (free, NO API key)** and passes it as `AiBrain.ask(extraContext=…)`
      so the model phrases it warmly in Telugu. Fetch fails silently → normal chat, never an error.
      VERIFIED on emulator: asking "rain" fired the trigger and logged real facts
      (`Weather facts: … rain 73% today, 57% tomorrow`, matches the live API). The final warm-Telugu
      reply needs the OpenRouter key in Settings (this install had none → "AI సెటప్ కావాలి"); the AI
      round-trip itself is already verified separately.
- [x] **Shipped: GitHub v0.2** release (signed APK). Repo https://github.com/alokflows/ammamma-companion

### Usability test — "Kamala" (grandmother sub-agent, no context) — 2026-07-06
Loved the call flow (one tap → big red hang-up). Confusions found → fixes:
- [x] FIXED: blank-number cards now dimmed + no call badge; tap shows a Telugu "ask family"
  hint instead of dropping her into the English edit form. (Edit stays on long-press.)
- [x] FIXED: Settings gear opens only on LONG-press (single tap = Telugu hint), so she can't
  wander into the API-key screen.
- [x] FIXED: Telugu-ized Edit dialog + Settings labels (bilingual Telugu · English).
- [x] FIXED: Talk screen — clear "వింటున్నాను…"/"ఆలోచిస్తోంది…" states, kinder failure copy,
  and a **text box (type-to-talk)** so it works even where there's no speech engine.
- [ ] TODO: real face photos on cards (recognition-by-face); needs a photo-picker in edit.

### Usability test — "Saroja" (2nd grandmother sub-agent, cannot READ, no context) — 2026-07-06
Drove the app blind on the emulator. Core truth she surfaced: **anything shown only as TEXT is
invisible to her; when the app has nothing to SAY, she assumes she broke it.** Loved the home
clock, the family cards, and the warm voice ("Cheppamma, emi kavali"). Real findings + status:
- [x] FIXED: Talk screen no longer goes SILENT. Tapping the mic with no AI key / no speech engine
  now SPEAKS the guidance (not just a text line), and no longer auto-pops the keyboard (a keyboard
  reads as "you must type" and scared her). AI-failure replies are spoken too. Verified on emulator.
- [ ] BLOCKER (real device): mic must actually work out of the box — needs the OpenRouter key set in
  Settings once; she has no typing fallback. STT itself only testable on her phone.
- [ ] BLOCKER: contacts ship empty ("no number") and the only way to add one is a typed form → she
  can't place a single call unaided. Needs family-prefilled contacts or a setup flow (Recorder Studio).
- [ ] HIGH: real photos on face cards — identical grey silhouettes force reading the name.
- [ ] MED: Back exits to the launcher (she got lost); consider returning Home instead of exiting.
- [ ] MED: speak the greeting + person's name on tap; make the gear a spoken "family setup"; drop
  stray English labels.
- NOTE (test artifacts, NOT bugs): she first saw the Talk screen only because a prior test left the
  app there — real launch is Home. Mic "dead" on emulator = no speech engine + no key, not a code bug.

### Talk + voice on the EMULATOR (for laptop testing) — 2026-07-06
- Emulator has NO Google speech engine → mic STT can't work here (only on her real phone).
  Workaround for testing: **type-to-talk** text box on the Talk screen.
- Installed **eSpeak-NG TTS** (`com.reecedunn.espeak`, F-Droid) on the emulator and set it as the
  default TTS (`settings put secure tts_default_synth com.reecedunn.espeak`). Now the app SPEAKS
  Telugu on the emulator (robotic; her phone's Google TTS is far better). Verified: battery lines
  and AI replies are spoken. Full talk-by-text→Telugu-reply→spoken loop VERIFIED on emulator.

### AI / model choice (IMPORTANT)
- Gemini direct API keys all returned `limit: 0` (no free tier) — abandoned. Using **OpenRouter**
  (`sk-or-v1-...`, in Settings). Models (fallback list in `AiBrain.MODELS`, free tier):
  `google/gemma-4-31b-it:free` (warm Telugu, ~1.6s) primary, `openai/gpt-oss-120b:free` backup.
  Model slugs churn — re-check the free list if calls 404. Free tier is plenty for her usage.
- Free models are TEXT-only → flow is STT → LLM → TTS (not audio-in). AI is online-only.

## 6. Roadmap — NEXT (in priority order)
1. **Talk companion — finish on the REAL device:** brain works (`AiBrain` via OpenRouter). Left:
   - Verify SpeechRecognizer (te-IN) STT + Telugu TTS on her phone (untestable on emulator).
   - Hands-free loop: after speaking the reply, auto-reopen mic; stop on 10s silence or manual stop.
   - Call-intent: if she says "call <person>", auto-dial that Contact instead of chatting.
   - Offline: command-match + photo buttons, never an error.
2. ~~**Weather (Telugu)**~~ **DONE** — `Weather.kt` (Open-Meteo, no key) → `AiBrain.extraContext`,
   emulator-verified. Optional future polish: a weather tile on Home, and half-day/tomorrow phrasing.
3. **Grandpa finder** — core done (§7); optional "finder role" toggle remains.
4. **Good-morning heartbeat**, **talking alarm**, **hourly time chimes** (24 clips, manifest).
5. **Recorder Studio** (family records clips + per-contact "<name> chestunnaru") — Alok's LAST step.
6. **Real contacts/photos** editing UI (replace `Contacts.sample`).
7. ColorOS survival: guide auto-start + battery-whitelist + overlay on the real device (day 1).

## 7. Grandpa finder
Grandfather has a good phone; install the SAME app there in a "finder" role.
- **[DONE] Ring:** grandpa texts the code word → her phone rings loudly.
- **[DONE] Location reply:** on the code-word SMS, `LocationReplyService` reads her GPS and
  texts a `maps.google.com/?q=lat,lon` link back to the sender (GPS + SEND_SMS; no internet
  needed on her side). Runs as a brief foreground service (correct O pattern).
- **[TODO] Grandpa role:** a toggle in Settings ("This is the finder phone") → home screen
  becomes one big "Find Ammamma's phone" button that sends the SMS and opens her replied link.
- **Honest note:** true AirTag-style proximity (UWB / crowd network) is NOT feasible without
  special hardware; ring + GPS-SMS is the right tool for this need.

## 8. Key decisions & rationale
- **targetSdk 27** (= her OS): old permission/background rules the device expects;
  also allows launching activities from background (find-phone, charging card).
- **No on-device AI model** — 2 GB RAM can't run one; Alok disliked Gemma. AI lives in
  the cloud (Gemini) only when online; offline uses command-match + photo buttons.
- **Full-screen Activities, not SYSTEM_ALERT_WINDOW overlays** — no ColorOS overlay
  permission dance; "just works" on day one.
- **Clip-first Announcer** — swap family voices with zero rebuild (drop files in `clips/`).
- **Reuse the platform, not whole apps** — SMS/service/battery are ~50 lines of standard
  Android; a 3rd-party app would add APK bloat on a storage-constrained phone. Leverage
  official SDKs (Gemini) + free APIs (Open-Meteo) where they compound.

## 9. Security (Apple standard)
- Settings/Alert/Charging/FindPhone activities are `exported="false"` (only the app opens them).
- SMS receiver requires `BROADCAST_SMS` (only system telephony can deliver) + code word
  (+ optional family-number allowlist). Recommend Alok set family numbers to lock it.
- Gemini API key stored in app-private SharedPreferences only. NEVER log it, NEVER commit it.
- Dangerous permissions (CALL_PHONE, RECEIVE_SMS) requested at runtime; graceful fallbacks.

## 10. Open questions (test on real device, day 1)
- Google TTS offline Telugu voice quality on this device? (Emulator has no TTS engine.)
- WebView version — can it play m.youtube.com for cooking videos?
- ColorOS SYSTEM_ALERT_WINDOW / auto-start reliability.
- Exact cheapest Gemini model with audio input (verify at build time).

## 11. v0.6 (2026-07-07) — real-device bug report round 2
Fixes driven by Alok's on-device testing:
- **Voice stops the INSTANT a call is answered/ended.** stopCallerLoop() now calls
  Announcer.stopSpeaking(); previously it only cancelled the *next* repeat, so the
  current sentence ran 1-2s over the live call.
- **Long names are never chopped.** The repeat loop skips its tick while
  Announcer.isSpeaking() (re-checks every 800ms) instead of QUEUE_FLUSHing mid-word.
- **"Someone is calling" can no longer replace the real name.** The number-less
  RINGING broadcast waits 1.5s (UNKNOWN_GRACE_MS) for its numbered twin before
  announcing unknown — the two goAsync threads used to race and the generic line
  could land in the service AFTER the name.
- **Silent announcements after process death fixed.** TTS init is async; when an
  incoming call resurrects the app, the first speak() used to fail silently. Now the
  line queues in Announcer.pendingText and onInit speaks it (verified: 19ms after
  "TTS ready" on a kill→call test). stopSpeaking() clears the queue too, so a call
  answered during engine startup never speaks late.
- **AI is provider-agnostic with real errors.** Settings has base URL (default
  OpenRouter), key, model + "Get models" (live GET /models list, free-first, verified
  343 models) + "Test AI" (shows exact HTTP status/body in a dialog). AiBrain speaks
  a DIFFERENT Telugu line per failure: 401/403 key wrong, 402 credits, 400/404 model
  dead, 429 genuinely busy. The old code called everything "busy" — which is how a
  never-used key masqueraded as rate-limiting. Talk screen prints r.detail small for
  the family. AiBrain.ask() signature is now ask(context, text, extra).
- **Travel Mode** (Settings toggle, off by default): every charger plug/unplug
  silently texts GPS location to ALL family numbers via the generalized
  LocationReplyService (multi-recipient + message prefix). No internet needed.
  NOT included, with reasons: toggling airplane mode / mobile data programmatically
  is impossible for normal apps since Android 4.2/5 (needs system privileges), and
  front/back camera photos need internet to deliver — parked until there's a channel.
- **ColorOS SMS popup**: the countdown "send SMS?" dialog is ColorOS's guard, not
  ours — SETUP_PHONE.md step B2 (Send SMS → Allow) removes it. No code fix exists
  short of becoming the default SMS app (bad idea).

## 12. v0.6 round 2 — grandma-agent review (2026-07-07)
A Sonnet agent role-played Ammamma exploring the app on the emulator. FIXED same day:
home-greeting spoken; "calling <name>" spoken as the call places; no-number and
settings-gate taps speak Telugu; mic didn't-hear is spoken; Talk keyboard stateHidden;
raw HTTP detail removed from her screen (family uses Settings→Test AI).
OPEN — needs Alok's decision (deliberately not done unilaterally):
- Back on Home exits to the raw launcher (no kiosk/lock-task mode).
- Long-press on a face opens Edit/Delete — a shaky finger can reach it (gate harder?).
- "+ Add person" tile is one tap away, same style as real cards.
- No visible "stop talking" control anywhere in the UI.
- Green call badge could read as "on a call now".
Temporary test rig: scratchpad sonnet_bridge.py (localhost:8899, OpenAI-shaped,
backed by `claude -p --model sonnet`) — emulator's ai_base_url points at
http://10.0.2.2:8899/v1 with model claude-sonnet-5. Kill the python process when done;
real phone should use OpenRouter + the Test AI button.

## 13. v0.9 (2026-07-11) — real-device bug round 3, RELEASED ✅
Released at /releases/tag/v0.9 (signed APK). NOTE: v0.7 and v0.8 tags were cut by
Gemini/Antigravity (model routing, multi-key, TheftGuard) — this repo is co-edited by
another AI; check `gh release list` before tagging.

Two-agent batch (disjoint file ownership), merged at b76a8ba, verified on emulator:
- **Voice discipline**: greeting once per session (30-min rule, `Announcer.shouldGreetNow`),
  portrait lock, one-voice rule (clip vs TTS), volume % + mute toggle + `important:` param
  (find-phone/low-battery bypass mute at full volume), Sounds settings section,
  charging speaks FIRST on plug-in.
- **Repeating alerts**: AlertActivity re-speaks its line for `Settings.alertRepeatSeconds`
  (default 30, 0=once); stops on charger connect/disconnect, OK tap, snooze, or leaving
  the card. Verified live: armed → 4 repeats → cancelled on AC and on tap.
- **Talk rewrite**: chat-bubble UI, speak bar + typing bar, auto-listen loop that never
  listens while TTS speaks, sessions in filesDir/chats with list/new/delete + 30-day
  auto-delete (`chat_auto_delete`, default ON — Settings switch not yet built), full-length
  answers with history (~16 msgs), digit-extraction dials exactly what she said.
- **Travel mode**: separate travel recipient list + per-channel toggles; WhatsApp DROPPED
  (no silent auto-send on Android); email relay documented in TRAVEL_EMAIL.md only.
- **Startup race FIXED (3ebfe1f)**: app-launched cards (Alert/Charging/FindPhone) now carry
  `FLAG_ACTIVITY_NO_USER_ACTION`. Without it, Android fired `onUserLeaveHint` on MainActivity
  when our own card appeared → `stopSpeaking()` wiped the queued cold-start greeting ~2ms
  after service start (emulator: sticky battery broadcast at charging/100% pops the
  battery-full card at every service start). Any FUTURE background-launched activity must
  carry this flag too.

Emulator gotchas (cost us a day): this AVD needs `adb emu power status charging`
(`ac on` alone leaves status=NOT_CHARGING); verify the installed APK via
`dumpsys package com.ammamma.companion | grep lastUpdateTime` (debug can't overwrite a
release-signed install — uninstall first); check `voice_muted` in shared_prefs before
judging silence a bug.

Next: Alok runs SETUP_PHONE.md + installs v0.9 on the real Oppo → bug round 4; then
backlog: chat_auto_delete Settings switch, real face photos on dial cards, Recorder
Studio, good-morning heartbeat, talking alarm, hourly chimes, weather tile, §12 UX
decisions, TRAVEL_EMAIL.md relay client.
