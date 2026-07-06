# PRODUCTION_READY.md — Ammamma's Phone Partner

> **You are a fresh Claude Code session. Read this whole file, then EXECUTE the plan in §12.**
> This is the single source of truth. It is self-contained — you need no other context.
> Work like a top-0.1% product engineer: think it through thoroughly, use common sense,
> test everything on the real OS (an emulator that mirrors the device), spin up a
> "grandmother" test agent, fix what she finds, then ship. Keep this file updated as you go.
>
> **Guiding law:** the north star for every decision is *"will this make grandma love her
> phone?"* Reliability > cleverness. Boring, readable Kotlin. Prefer the platform/stdlib over
> libraries (the "ponytail" habit). NEVER leave a feature that only "half" works — a one-shot
> announcement that should repeat, a button that does nothing, a screen in English she can't
> read: these are bugs. Think about what a real, clever elderly woman actually needs.

---

## 1. Mission & user
Turn an old phone into a **talking companion** for **Ammamma**, an elderly, Telugu-only
grandmother in **Huzurabad, Telangana**. She is clever and independent but cannot read screen
text reliably and does not use apps. The phone must be **audio-first, photo-first, offline-first**,
and feel like a small partner she loves — not a gadget she fears. She said she'd save money for a
new phone just to have a voice companion; she shouldn't have to.

**Do NOT treat her as a "dummy."** She learns. Show real information (who's calling, battery %,
health, time-to-full) — just clearly, large, and spoken in Telugu.

## 2. Target device & hard constraints
- Oppo CPH1853 (A3s), ColorOS 5.2.1, **Android 8.1.0 (API 27)**, Snapdragon 450, **2 GB RAM**,
  16 GB storage (nearly full). ColorOS kills background apps aggressively.
- **Offline-first:** everything except the optional AI companion must work with **zero internet**,
  and never show an error when offline — fall back to what works.
- **Sideloaded** (no Play Store). `minSdk 26`, `targetSdk 27` (matches the device so Android
  applies the old, simpler background/permission rules — do NOT bump targetSdk).
- **Cable is charge-only** → all dev/test is on the emulator; final install is over Wi-Fi
  (Telegram → Saved Messages → tap APK → allow "install unknown apps").

## 3. Dev environment (macOS, Apple Silicon)
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17     # JDK 17 (keg-only)
export ANDROID_SDK_ROOT=~/Library/Android/sdk
ADB=~/Library/Android/sdk/platform-tools/adb
```
- Emulator AVD: **`ammamma_oppo`** (API 27, google_apis, arm64, tuned to 2 GB / 720x1520).
- Gradle: use the wrapper `./gradlew` (pinned 8.7; AGP 8.5.2, Kotlin 1.9.24). System gradle is too new.
- Project root: `/Users/megha/AmmammaCompanion`. Package `com.ammamma.companion`. Our log tag is `Ammamma`.

### 3a. Make the emulator SPEAK (critical for testing voice)
The `google_apis` emulator has **no speech engine** — no TTS and no STT. So install eSpeak-NG
(FOSS, has a Telugu voice) and set it default, or every announcement is silent and Talk says "try again":
```bash
curl -sL -o /tmp/espeak.apk https://f-droid.org/repo/com.reecedunn.espeak_22.apk
$ADB -e install -r /tmp/espeak.apk
$ADB -e shell settings put secure tts_default_synth com.reecedunn.espeak
$ADB -e shell settings put secure enabled_tts_engines com.reecedunn.espeak
```
eSpeak is robotic — it's only a test stand-in. On her real phone, Google Telugu TTS sounds natural.
**Voice-IN (STT) cannot be tested on the emulator** (needs Google app). Test Talk by TYPING; on her
phone she speaks. Announcements/TTS-out ARE testable here via eSpeak.

## 4. Build / install / test cheatsheet
```bash
cd /Users/megha/AmmammaCompanion
export JAVA_HOME=/opt/homebrew/opt/openjdk@17; export PATH="$JAVA_HOME/bin:$PATH"

# Boot emulator if needed (first boot ~15s):
$ANDROID_SDK_ROOT/emulator/emulator -avd ammamma_oppo -no-snapshot -gpu auto -no-boot-anim &
$ADB wait-for-device

./gradlew assembleDebug --no-daemon
$ADB -e install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -e shell am start -n com.ammamma.companion/.MainActivity
$ADB -e logcat -d -s "Ammamma"                        # our logs
$ADB -e exec-out screencap -p > /tmp/shot.png         # then Read the PNG to SEE the screen

# Simulate battery:
$ADB -e shell dumpsys battery unplug
$ADB -e shell dumpsys battery set level 18            # -> low card + spoken warning + repeat
$ADB -e shell dumpsys battery set ac 1; $ADB -e shell dumpsys battery set status 2   # charging
$ADB -e shell dumpsys battery reset

# Simulate an INCOMING CALL (caller announcement, repeats while ringing):
$ADB -e emu gsm call 9876543210         # ring from a number
$ADB -e emu gsm cancel 9876543210       # hang up -> announcement stops

# Simulate find-my-phone SMS:
$ADB -e shell pm grant com.ammamma.companion android.permission.RECEIVE_SMS
$ADB -e emu sms send 15551234567 "please FINDME now"

# Mock GPS (Huzurabad) for grandpa finder:
$ADB -e emu geo fix 79.0339 18.2039
```
**Gotchas:**
- Activities with `exported="false"` (Settings, Alert, Charging, FindPhone, Talk) CANNOT be
  launched with `adb am start` (Permission Denial — correct). Reach them via the in-app path
  (tap the gear/talk button, trigger the event), using `input tap X Y` (screen 720x1520).
- To seed the OpenRouter AI key + test contacts without typing, write the prefs via `run-as`
  (debug build) to `/data/data/com.ammamma.companion/shared_prefs/ammamma_settings.xml`.
- Battery-optimization whitelist for the service: `$ADB -e shell dumpsys deviceidle whitelist +com.ammamma.companion`.

## 5. Current state (v0.3) — files & what works (all emulator-tested)
Sources in `app/src/main/java/com/ammamma/companion/`:
| File | Role |
|---|---|
| `MainActivity` | Home: live clock, photo-dial grid from `Contacts`, one-tap call, **"+" add tile**, long-press edit/delete, gear (long-press → Settings), Talk button. Requests ALL permissions on first run + battery-opt exemption. |
| `CompanionService` | Foreground heartbeat + **repeat engine**: caller announce repeats every 5s while ringing; low-battery reminder repeats every N min until charging. |
| `Announcer` | Single voice. Clip-first (`filesDir/clips/<key>.*`) → Telugu TTS fallback. Raises media volume. Singleton. |
| `CallReceiver` | On RINGING → look up number → `CompanionService.startCaller` (repeats "<name> ఫోన్ చేస్తున్నారు"). On OFFHOOK/IDLE → stop. |
| `BatteryWatcher` | Low (20/10%) → spoken warning + red card + start repeating reminder. Charging → stop reminder + connect/disconnect/full lines (all Telugu + %). |
| `ChargeState` / `ChargingActivity` | Live charging screen: %, health, self-computed time-to-full (API 27 has no system API), spoken. |
| `AlertActivity` | Giant full-screen card (low = red + **snooze**, full = green). Shows over lock screen. |
| `Contact` / `Contacts` | Contacts persisted as JSON in prefs. `load/save/update/add/remove`. Seeded with 6 relationship defaults. |
| `TalkActivity` | Voice companion: mic (SpeechRecognizer te-IN) OR **type-to-talk** box → `AiBrain` → Telugu reply shown + spoken. |
| `AiBrain` | OpenRouter (free models, 6-way fallback) over `HttpURLConnection`. Warm-Telugu system prompt. Online-only, optional. |
| `Settings` / `SettingsActivity` | Family-only (gated). Code word, family numbers, OpenRouter key, **battery reminder minutes**, **caller demo** button. Telugu-ized. |
| `FindPhoneReceiver` / `FindPhoneActivity` | Find-my-phone by SMS: alarm on silent + "ఇక్కడ ఉన్నా!". |
| `LocationReplyService` | Grandpa finder: texts the phone's GPS (maps link) back to the sender. |
| icon | Adaptive: small white Apple-style heart on a rose/pink gradient. |

**Shipped:** public repo `https://github.com/alokflows/ammamma-companion`, signed releases up to
**v0.3**. Signing via `keystore.properties` + `ammamma-release.keystore` (both gitignored, local only).

## 6. TEST PROTOCOL — run this every session (test ALL of it)
Build, install, set up eSpeak (§3a), then verify each with the §4 commands + a screenshot + logcat.
Confirm the EXPECTED result; if it fails, fix it. Do not trust "it compiles."
1. **Home** renders: clock, date, 6 face cards, "+" tile, Talk button, gear.
2. **Call**: tap a face WITH a number → real call placed (logcat `ACTION_CALL`). Faded (no-number) card → Telugu "no number" hint, NOT an English form.
3. **Caller announce REPEATS**: `emu gsm call` → logcat shows the line every ~5s; `emu gsm cancel` → it STOPS.
4. **Battery low**: drop to 18% → spoken warning + red card + snooze; reminder repeats (set interval to 1 min in Settings to test fast); plug in → stops + "charger connected, X%".
5. **Charging screen**: %, health, time-to-full, spoken.
6. **Find-my-phone**: SMS code word → alarm screen; with GPS mocked → logcat shows the maps-link SMS reply.
7. **Settings** (long-press gear): Telugu labels, no keyboard auto-pop; **Caller demo** speaks; battery-minutes saves.
8. **Add / edit / delete** a contact; grid updates; new number is callable.
9. **Talk** (type-to-talk, needs AI key + internet): type → warm Telugu reply shown + spoken; graceful message if the free models are busy (429).
10. **First run** (fresh install): permission prompts appear for all groups + battery-opt.

## 7. Spin up the GRANDMOTHER test agent (do this near the end, after fixes)
Launch a fresh general-purpose sub-agent with NO context, playing a clever 60-year-old Telugu
grandmother. Give her ONLY: the persona, that this is "her phone" (the running emulator), and the
mechanical adb commands (screencap+Read to see, `input tap/swipe`, keyevent BACK/ENDCALL) — NOT what
the app does. Tell her to explore, try to call family and talk to it, react honestly, do ~15 actions,
then write a usability report (what's easy, what confused her + exactly where, top-3 fixes). Then
**fix her findings** and note them here. (Her past findings are in §9 — don't regress them.)

## 8. COMMON-SENSE FEATURES STILL MISSING — build these (prioritized)
Each has an acceptance test. Think about the real need before coding. Mark done in §5 as you go.

### P0 — core reliability & common sense
- [ ] **Start on boot.** A `BootReceiver` (RECEIVE_BOOT_COMPLETED) starts `CompanionService` so the
  companion is alive after a reboot even if she never opens the app. *Accept:* reboot emulator
  (`adb -e reboot`), wait, confirm the service runs and battery/caller announcements work without opening the app.
- [ ] **Spoken "calling <name>" confirmation.** When she taps a face, speak "కూతురుకి కాల్ చేస్తున్నా"
  before/as it dials, so she knows the right person is being called (guards against a misread tap).
  *Accept:* tap daughter → hear the confirmation, then the call.
- [ ] **Battery-FULL reminder repeats** gently until unplugged (same pattern as low). Right now full
  announces once. *Accept:* at full+charging, "charger teeseyandi" repeats every N min until unplugged, then stops.
- [ ] **Confirm before DELETE contact** (accidental delete = lost number). *Accept:* delete shows a
  Telugu confirm dialog.
- [ ] **Announcements loud enough over the ringtone.** The caller announcement must be clearly audible
  while the phone is ringing (consider raising ring/alarm volume, or ducking). *Accept:* verify audibility logic.

### P1 — the heart of the product (from the original brief)
- [ ] **Contact photos.** A photo picker in the edit dialog (ACTION_GET_CONTENT / camera); store the
  image in app storage; show the real face on the card AND ideally on the incoming-call/confirmation.
  Recognition-by-face is her whole mental model. *Accept:* set a photo, it shows on the card.
- [ ] **Recorder Studio** (family-only, in Settings). Lists the phrases/events that need a voice
  (`caller_<i>` per contact, `battery_low`, `charger_connected`, `charger_removed`, `battery_full`,
  `talk_greeting`, `good_morning`, time chimes). Record → playback → save to `filesDir/clips/<key>.*`.
  The `Announcer` already plays clips before TTS, so recorded family voice then replaces the robot with
  zero code change. *Accept:* record a clip for `caller_0`, trigger that caller → the clip plays, not TTS.
- [ ] **Speak the time on demand.** Make the big clock tappable → speaks "ఇప్పుడు X గంటలు" in Telugu.
  *Accept:* tap clock → hears the time.
- [ ] **Good-morning heartbeat.** A daily spoken line at a set time (AlarmManager, exact-while-idle).
  Time configurable in Settings. *Accept:* set time to +2 min, hear the greeting.
- [ ] **Missed-call announcement.** When the screen turns on / she picks up the phone, announce missed
  calls ("రెండు కాల్స్ మిస్ అయ్యాయి"). *Accept:* miss a call, wake screen → spoken.

### P2 — enrichment (the "cherry", mostly online)
- [ ] **Talk: hands-free loop** — after speaking the reply, auto-reopen the mic; stop on 10s silence or
  a manual stop. (Device-only; STT needs her phone.)
- [ ] **Talk: call-intent** — if she says "అమ్మాయికి కాల్ చేయి"/"call daughter", auto-dial that contact
  instead of chatting. Offline fallback = the photo buttons.
- [ ] **Weather (Telugu).** Open-Meteo (free, NO key) for Huzurabad (lat 18.20, lon 79.03) → pass the
  forecast into `AiBrain.ask(..., extraContext=...)` so rain answers are accurate, not guessed.
- [ ] **Talking alarm / hourly time chimes** — she sets an alarm that speaks; optional hourly chime.

## 9. Design principles & the grandmother's past lessons (do not regress)
- Recognition over reading; one tap = one job; warm not childish; huge targets; Telugu everywhere she
  can reach. Green = call. Each person a colour. Family voices are the soul.
- "Kamala" (test agent) already taught us, and these are FIXED — keep them fixed: no-number cards are
  dimmed + labelled "నంబర్ లేదు" and never open an English form on tap; Settings is gated behind a
  long-press and Telugu-ized; the Talk screen has clear states + a text box; the caller announcement repeats.
- Every user-facing string she might see must be Telugu. Technical/family screens can be bilingual but
  must be gated away from her.

## 10. Security & ship process
- Signed release: `./gradlew assembleRelease` (uses local `keystore.properties`; NEVER commit it or the
  `*.keystore` — they're gitignored). No API key is in the repo (entered on-device via Settings).
- Bump `versionCode`/`versionName` in `app/build.gradle.kts` for each release.
- Push, then `gh release create vX.Y app/build/outputs/apk/release/app-release.apk --title ... --notes ...`.
- Confirm no secrets staged: `git ls-files | grep -iE "keystore|sk-or-v1"` must be empty.

## 11. Known truths (don't rediscover the hard way)
- Emulator has no Google TTS/STT → use eSpeak for TTS-out; type-to-talk for Talk; STT only on device.
- OpenRouter free models return **429 under load** — AiBrain already has a 6-model fallback; the AI is
  optional and must never block the offline app.
- `dumpsys battery reset` can fire a "battery full" alert (it sets charging 100%) — expected in tests.
- `adb emu geo fix` doesn't reliably update *last-known* GPS on the emulator (returns Googleplex); the
  location logic is correct — a real device returns the real spot.

## 12. YOUR PLAN FOR THIS SESSION (execute in order)
1. Boot the emulator; set up eSpeak (§3a); build + install (§4).
2. Run the FULL test protocol (§6). Note anything broken.
3. Build the **P0** common-sense features (§8), testing each with its acceptance test.
4. Build as many **P1** features as time allows (photos + Recorder Studio + speak-the-time are highest value).
5. Spin up the **grandmother agent** (§7); fix her findings; don't regress §9.
6. Re-run the test protocol; make sure everything still passes.
7. Bump version, build signed release, push, cut a GitHub release (§10).
8. **Update this file** (§5 done-list, §8 checkboxes, §9 lessons) so the next session starts clean.
Think it through thoroughly at each step. When you finish a feature, ask: "does this actually help
grandma, and does it fail gracefully?" If not, it's not done.
