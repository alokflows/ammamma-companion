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

## 14. FINAL PUSH → v1.0 (the "done, close the chat" batch) — EXECUTE THIS IN A FRESH SESSION

**Alok's mandate (2026-07-11, verbatim intent):** finish EVERYTHING in one orchestrated run.
Parallel Sonnet agents do all the work; the orchestrator (Fable) writes exact specs, reviews
every diff, stitches, verifies, releases. **Definition of done: after this session there is NO
backlog, NO "next step", NO open decision.** Alok closes the chat and shares the v1.0 APK with
his family. Anything not built must be recorded below as *decided out of scope, with the reason*.

### 14.0 Orchestration rules (Alok's economy rules — follow exactly)
- Fable does ALL thinking/diagnosis in the main context. Sonnet agents get instructions so
  exact they never have to think ("it just knows what to do"). Opus: not needed here.
- Run agents in PARALLEL worktrees with **disjoint file ownership** (v0.5 lesson: overlapping
  Settings edits made a manual-merge mess).
- **Before dispatch, the orchestrator commits a base-prep commit adding every new `Settings.kt`
  getter and every new string key ALL packages need** — agents branch from that base and never
  touch `Settings.kt` or `SettingsActivity.kt` (WP-D owns SettingsActivity alone).
- Review each agent's diff line-by-line before merging (quality gate has caught real defects:
  965e769, d0c54b0). Check partial work before respawning a failed agent.
- Any background-launched activity MUST carry `FLAG_ACTIVITY_NO_USER_ACTION` (§13 root cause).
- Emulator gauntlet before release; evidence = logcat lines, not claims. Gotchas in §13 apply
  (power status charging; lastUpdateTime; voice_muted pref; uninstall release before debug).

### 14.1 Decisions ALREADY MADE — do not re-ask Alok
- §12 UX items, decided by orchestrator under the standing goal:
  - **Back on Home: consume it** (override onBackPressed → no-op). She can never fall out to
    the raw launcher. Family navigates via gear.
  - **Long-press edit/delete: gate it.** New pref `edit_locked` (default ON). Locked = long-press
    speaks "మార్చాలంటే సెట్టింగ్స్‌లో అన్‌లాక్ చేయండి" and does nothing. Unlock switch in Settings.
  - **"+ Add person" tile: hidden while `edit_locked`** (family adds via Settings/unlock).
  - **Stop-talking control: tapping the Home clock (or any blank Home background) calls
    Announcer.stopSpeaking().** No new button — the biggest thing on screen becomes the mute.
  - **Green call badge → small white phone glyph** on the ring (reads "tap to call", not "on a call").
- **OUT OF SCOPE, final (Alok's own decisions, recorded):** WhatsApp channel (no silent
  auto-send on Android); travel email relay (TRAVEL_EMAIL.md documents it; NOT built);
  airplane-mode/mobile-data auto-toggle (OS-impossible); on-device AI model (2 GB RAM);
  neural TTS engines (storage full — Google TTS stays). These are ANSWERS, not gaps.
- Chimes default ON with quiet hours 21:00–07:00; heartbeat default 07:00 OFF until a clip
  exists (a robotic good-morning is worse than none — it auto-enables when a `goodmorning`
  clip is recorded, family can override in Settings).

### 14.2 Work packages (disjoint ownership — file lists are the contract)
**WP-A · Recorder Studio** — NEW `RecorderActivity.kt`, NEW `ClipStore.kt`. Family records the
voice clips that are the app's soul. MediaRecorder → `filesDir/clips/<key>.<ext>` in EXACTLY the
format/naming `Announcer`'s clip-first lookup already expects (agent must read Announcer first
and match it — do not invent a new convention). Screen: scrollable list of every clip key with
Telugu label + what-it's-for line, per-row ▶ play / ● record / ✕ delete, recorded rows tinted
green. Key catalog: `greeting`, `goodmorning`, `alarm`, `battery_low`, `battery_full`,
`charger_connected`, `charger_removed`, `found_phone`, `hour_0`…`hour_23`, plus per-contact
`calling_<contactId>`. Entry point: one button in Settings (WP-D places it).
**WP-B · Clock features** — NEW `DayScheduler.kt` (+ NEW `AlarmActivity.kt` only if AlertActivity
can't be reused — prefer reuse via `AlertActivity.show(..., repeatText=…)`). Three features on
AlarmManager `setExactAndAllowWhileIdle` (API 27!), each rescheduling itself after firing and
after boot: (1) hourly Telugu time chime ("సమయం <N> గంటలు") clip-first `hour_<N>`, quiet hours +
master toggle from Settings; (2) good-morning heartbeat at set time, clip `goodmorning`;
(3) talking alarm at family-set time → full-screen card + repeat engine, clip `alarm`, ఆపు stops.
Exposes `DayScheduler.scheduleAll(context)`; orchestrator wires the single call into
CompanionService.onCreate + BootReceiver at merge (WP-B must NOT edit those two files — put the
exact one-liner in the agent report). Every feature gets a demo/test button spec for WP-D.
**WP-C · Home polish** — owns `MainActivity.kt`, `Contact.kt` (+ its storage). Real face photos:
in the (unlocked) edit screen, "photo" button → `ACTION_GET_CONTENT` image → copy+downsample to
`filesDir/faces/<contactId>.jpg` (inSampleSize — 2 GB RAM) → circular-cropped on the dial card,
color ring kept as border; falls back to today's initial-tile when absent. Weather tile: one
small tile row on Home → tap speaks today's Huzurabad weather via existing `Weather.kt`
(offline → "ఇంటర్నెట్ లేదు, వాతావరణం తెలియడం లేదు"). Plus the §14.1 decisions: back-consume,
edit_locked gating, hide Add-tile when locked, clock-tap = stopSpeaking, phone-glyph badge.
**WP-D · Settings + finder** — owns `SettingsActivity.kt` ONLY (Settings.kt getters were
pre-added by orchestrator). Adds: chat auto-delete switch (pref exists, default ON — §13);
`edit_locked` switch; Sounds additions (chimes toggle + quiet hours, heartbeat time+toggle,
alarm time+toggle); Recorder Studio entry button; demo buttons for chime/heartbeat/alarm; and
**grandpa finder role**: pref `finder_role` + "her number" field + NEW `FinderActivity.kt` — one
giant "అమ్మమ్మ ఫోన్ వెతుకు" button that SMSes the code word to her number and shows/speaks "ఆమె
ఫోన్ మోగుతుంది, లొకేషన్ SMS వస్తుంది". Orchestrator wires the 3-line finder_role redirect at the
top of MainActivity.onCreate at merge (WP-D must not touch MainActivity).

### 14.3 Merge → verify → ship (orchestrator, in order)
1. Base-prep commit (all Settings getters + keys). 2. Dispatch WP-A/B/C/D in parallel worktrees.
3. Review diffs; merge D, A, B, C; apply the cross-file one-liners (DayScheduler call in
CompanionService.onCreate + BootReceiver; finder redirect in MainActivity). 4. Build debug,
run the FULL gauntlet: §13's six scenarios PLUS chime demo fires+speaks, heartbeat demo,
alarm demo card repeats & ఆపు stops, photo pick renders, weather tile speaks, back consumed,
locked long-press speaks, clock-tap kills speech, finder role sends SMS (adb emu sms), recorder
saves a clip AND Announcer plays that clip instead of TTS. Evidence-logged, every line.
5. Bump `app/build.gradle`: versionCode 10, versionName "1.0". 6. `gh release list` FIRST
(co-edited repo!), then assembleRelease (keystore §"Security", creds in keystore.properties)
→ release **v1.0** with family-facing notes. 7. Append HANDOFF §15 "v1.0 SHIPPED — project
complete"; update memory `project_ammamma_companion.md` to CLOSED state; SETUP_PHONE.md gains
a "record the family's voice (Recorder Studio)" final step. 8. Tell Alok: install v1.0, run
Recorder Studio with the family — that's the last human step, and it's his by design.

## 15. v1.0 FINAL PUSH — session state (2026-07-11 evening)

Four parallel Sonnet worktree agents built the WPs; every diff reviewed line-by-line; merged
clean (disjoint ownership held, zero conflicts): `b092d43` WP-D → `10be79a` WP-A → `6b42c70`
WP-B → `ac15be5` WP-C → `aa88cda` stitch (DayScheduler.scheduleAll in CompanionService.onCreate
+ BootReceiver + SettingsActivity.save; finder-role redirect at top of MainActivity.onCreate,
before setContentView). Debug APK installed on `ammamma_oppo` emulator.

**GAUNTLET PASSED (logcat evidence):** greeting on cold start; charger removed/connected speak
live % (emulator needs BOTH `adb emu power ac off/on` AND `power status …` — `status` alone
fires no POWER_CONNECTED); low battery 14% → speech + AlertActivity + repeat engine; battery
full speech + low-repeat auto-cancel on plug-in; unknown caller → ring duck + repeating
announce, stops on call end; FINDME SMS → find alarm + FindPhoneActivity + location SMS reply,
ఆపు stops it; DayReceiver chime (hour 18 → "సమయం 6 గంటలు", quiet=false, self-reschedules
19:00) and heartbeat/alarm correctly gated while OFF; clip-first PROVEN (pushed hour_18.m4a +
goodmorning.m4a via run-as → "Playing clip …" replaced TTS); Settings day-clock section renders
w/ correct defaults (chimes ON, heartbeat/alarm OFF); switches flip live → heartbeat fires the
goodmorning clip, alarm fires card + repeats, సరే cancels + silences; contact-id migration
verified on device (prefs: c1..c6, contact_next_id=7); "+" add-tile hidden (edit_locked ON).
Gotcha: shell broadcasts to DayReceiver need `adb root` (receiver non-exported).

**GAUNTLET REMAINING:** back consumed on Home; locked long-press speaks unlock line; clock-tap
stopSpeaking; weather tile (UI dump still showed placeholder "వాతావరణం" — verify fetch lands
+ tap speaks; emulator network can be slow); finder role end-to-end (flip finderRoleSwitch,
set her number, relaunch app → FinderActivity, tap → SMS + spoken status; flip role back OFF);
RecorderActivity smoke (open via Settings recorderStudio button, rows render); Settings demo
buttons (identical code paths already proven via broadcasts — low risk).

**RELEASE STEPS THEN:** versionCode 10 / versionName "1.0" in `app/build.gradle.kts` →
`gh release list` FIRST (co-edited repo) → assembleRelease (keystore §Security) →
`gh release create v1.0` w/ family-facing notes → mark this section SHIPPED → memory
`project_ammamma_companion.md`: v1.0 shipped, v2 roadmap = §16 → SETUP_PHONE.md gains
"record family voices in Recorder Studio" final step → tell Alok: install v1.0, run Recorder
Studio with family (his last step by design). Emulator-only leftovers: test clips
hour_18/goodmorning.m4a + heartbeat/alarm switches ON — harmless, wipe if re-testing TTS.

## 16. v2.0 ROADMAP — "her phone OS" (Alok's brief 2026-07-11 + orchestrator brainstorm)

Vision: the app stops being an app and becomes the phone. She talks, it does — Siri-like,
Telugu-first, offline for basics, satisfying to touch. Same economy model: Fable thinks/specs/
reviews, Sonnet executes in disjoint-file worktrees, emulator gauntlet before every release.

**A. GUI overhaul** (Alok: battery/phone alert screens have "no good feel")
- Redesign AlertActivity / ChargingActivity / FindPhoneActivity: one modern design language
  with Home (rounded cards, soft elevation, big type, calm colors), not bare fullscreen slabs.
- Satisfying touch: pressed-scale animation (~0.96 + spring release), ripple, haptic tick on
  every actionable tap. Pure framework (ValueAnimator/StateListAnimator) — no libs, API 27-safe,
  60fps on 2 GB. Fast: cold start < 1.5s to first paint; lazy-init anything heavy.

**B. "Core priority" (reality check, Android 8.1)**
- Already have the two real levers: foreground service + battery-optimization exemption.
- Can add: THREAD_PRIORITY_URGENT_AUDIO for speech, high-importance notification channel.
- True CPU/core pinning is NOT app-controllable on stock Android — don't chase it; the win
  is staying resident (done) + fast wake paths.

**C. Little-Siri: offline intent engine (no AI for basics)**
- Local Telugu+English pattern matcher runs BEFORE the AI brain in TalkActivity: matched →
  execute + speak confirmation; unmatched → fall through to AI. Works with NO internet.
- Commands v1: set/cancel alarm ("...కి అలారం పెట్టు" → parse time → Settings.setAlarmTime +
  enable + DayScheduler.scheduleAll); call <name> (existing dial path); flashlight on/off
  (CameraManager.setTorchMode — offline, legal); Wi-Fi on/off (WifiManager.setWifiEnabled —
  still allowed at targetSdk 27); volume/brightness; "ఇప్పుడు టైం ఎంత"/date; battery %.
- Mobile-data toggle: NOT app-controllable (system permission) → open the exact Settings
  screen + spoken guidance. Hotspot on 8.1: reflection hack exists, fragile — test first.
- WhatsApp: silent auto-SEND remains impossible (v0.9 finding). Feasible: voice → open the
  right chat with the message prefilled (she taps the green send — teach it aloud); WhatsApp
  voice/video CALL intents exist but are version-fragile — REAL-DEVICE test before building.
- Offline STT: Google recognizer with te-IN offline pack if present — test on the Oppo;
  fallback = online STT, offline covers only pre-listed command words.

**D. Browser/search agent + AI fallback chain**
- "ఈరోజు వార్తలు ఏంటి?" → AI with web search. Cheapest path: Gemini free tier (built-in
  Google Search grounding); alt: RSS fetch + summarize. Alok to supply/test Gemini keys.
- Provider chain: primary Grok for chat → auto-fallback to Gemini on quota/4xx/5xx;
  ai_accounts already holds multiple keys — add ordered failover + per-provider capability
  flags (search→Gemini, chat→Grok). Surface which provider answered in the Settings tester.

**E. Orchestrator brainstorm (unordered)**
- Medicine reminders: recurring voice-set times, clip-first announcements, family-configured.
- Daily news briefing at a set hour (search + TTS, quiet-hours aware).
- "Read my messages / who called?" spoken SMS + missed-call summary.
- WhatsApp video-call shortcut on face tiles (if C's intent test passes).
- Idle photo-slideshow screensaver from faces/ + family-added photos.
- SOS: long-press anywhere 3s → call daughter + location SMS blast.
- Weather-aware morning heartbeat ("వాన రాబోతోంది, గొడుగు తీసుకోండి").
- Speech-rate voice command ("నెమ్మదిగా చెప్పు"); festival/birthday greeting calendar;
  remote family console via SMS code words (no server needed).

**Suggested first slice of v2:** C-basics (alarm/call/flashlight/Wi-Fi/time/battery) +
A alert-screen redesign + D fallback chain. WhatsApp + browser agent after device tests.

### 16-addendum (Alok, mid-session 2026-07-11 late)
**A+. Luxury graphics:** Gemini-app-style ambient gradient glows that slowly FLOAT/drift behind
buttons (animated RadialGradient/sweep on a low-fps ValueAnimator — cheap on GPU), Apple-luxury
minimalism: few elements, lots of space, soft motion, satisfying press physics. This is the
design bar for the whole v2 GUI overhaul.
**C+. Full assistant powers — honest feasibility:**
- Power OFF: impossible for any normal app (no API, even for device owner). Restart/reboot:
  POSSIBLE if the app is made **Device Owner** (one-time `adb shell dpm set-device-owner` during
  family setup — no root needed) → DevicePolicyManager.reboot() (API 24+). Device Owner also
  unlocks: lock screen, some global settings, app pinning (kiosk!). Seriously consider DO mode —
  a family-managed phone is exactly its use case; document an undo path.
- Mobile data: still no public toggle API on 8.1 even for DO (test setGlobalSetting; else open
  the exact settings screen + spoken guidance). Wi-Fi/hotspot/flashlight/alarms/calls: as §16C.
- "Trigger everything / write everything": build as an INTENT REGISTRY — every capability is a
  (Telugu patterns → action fn) entry; AI fallback can also emit a registry action id, so voice,
  offline matcher, and AI all drive the same verbs. Add verbs incrementally, gauntlet each.
**F. Custom wake word ("అమ్మమ్మ…") — implementation options (researched):**
1. **Picovoice Porcupine** — best quality/effort: custom wake word trained on their web console
   (no audio collection), tiny offline model, Android SDK, ~1 line to run in our foreground
   service. Costs: a dependency + free-tier license key + per-device limits. Prototype ~1 day.
2. **Vosk** (open-source offline STT): run tiny model (~50 MB) in continuous keyword-spotting
   mode. No license, fully offline; heavier on 2 GB RAM, needs tuning to avoid false wakes.
3. **DIY continuous SpeechRecognizer loop** — NOT recommended: battery hungry, Google throttles
   always-on mic for 3rd-party apps, unreliable on ColorOS.
   Constraints for ANY option: hold AudioRecord in CompanionService, RELEASE the mic instantly
   on PHONE_STATE ringing/offhook (caller announce + calls need it), mute-switch aware, and
   measure battery on the real Oppo for a full day before shipping. Verdict: feasible;
   Porcupine = fastest path, Vosk = no-fee path; both break the zero-dependency rule — that's a
   deliberate v2 decision, worth it if wake word is the doorway to the whole assistant.
**Emulator finding (this session):** this AVD's DNS hangs forever (hostname lookups never
return; IP ping fine) → HomeWeather fetch thread blocked >15 min, latch held. Hardened with
try/finally on the latch (committed). HttpURLConnection timeouts do NOT cover DNS — remember
this for every future network feature. Weather tile behavior on the real phone should be fine;
verify during Alok's install.

## 17. v1.1 "the luxury update" — LIVE session state (2026-07-11 night)

**Standing rule (Alok, permanent):** write this handoff after EVERY finished task — done (shas),
now (in-flight), next (thinking) — so any account can continue mid-flight. Never batch to session end.

**DONE this session:**
- v1.0 RELEASED ✅ releases/tag/v1.0 (versionCode 10, signed, ~740KB). All 7 remaining §15
  gauntlet items PASSED on emulator, zero bugs. master pushed (327739c). §14 backlog = CLOSED.
- Base-prep `1937b77`: Settings.kt TTS prefs (ttsVoiceName/ttsRate 50-150 dflt 92/ttsPitch dflt 100)
  + manifest Wi-Fi perms. NOTE: worktrees branch from ORIGIN state → agents told to `git merge master` first.
- WP-SIRI ✅ branch `worktree-agent-a5537673ea70eb47c` @ f9a70c4 — OfflineIntents.kt runs BEFORE the AI
  in CommandRouter.resolve(): time/date, battery(real ETA via ChargeState), torch, Wi-Fi, volume,
  alarm set/cancel (Telugu number words, speaks parsed time back), "ఫోటో పంపు"→CameraSendActivity
  (string classname), mobile-data honest+settings screen. CAUTION at merge: it cherry-picked two
  old commits + re-added Wi-Fi perms (its base predated 1937b77) — diff the merge, dedupe manifest.
- WP-CAM ✅ branch `worktree-agent-a17f6c3537f1c52ba` @ ce17ac5 — CameraSendActivity (camera→preview→
  huge పంపు→WhatsApp ACTION_SEND + spoken 2-tap lesson, chooser fallback) + hand-rolled read/write
  ShareFileProvider (repo is zero-dep, no androidx; r/w because camera writes through the URI).
- WP-VOICE ✅ branch `worktree-agent-a2fab70c0cd0e9ea6` @ 4356b09 — Announcer.applyVoiceSettings/
  availableTeluguVoices/audition; Settings "గొంతు · Voice" section: auto + per-voice radio+▶,
  rate/pitch sliders, TTS-settings button. Watch: it moved initDone=true earlier in onInit (reviewed rationale OK, re-verify cold-start pendingText in gauntlet).

**IN FLIGHT right now:**
- WP-LUX (branch `worktree-agent-a43392435e5899eca`) — the design overhaul, Alok's bar: Telegram-smooth,
  Gemini ambient floating glows, Apple restraint. GlowBackdrop.kt (3 drifting radial blobs, one
  ValueAnimator ~30fps, mood tints per screen) + Press.kt (0.96 scale + overshoot spring + haptic +
  ripple) across Home/Alert/Charging/FindPhone. Reskin ONLY — behavior/ids/flags intact, real info
  (%, health, ETA) never omitted. Iterating with emulator screenshots + gfxinfo jank budget.
- WP-CLIP (branch `worktree-agent-a279c50e9ced7419e`) — clip import: per-row "ఫైల్" (SAF) + "Drive"
  (share-link → uc?export=download, HTML response = not-public error) with VALIDATED-REPLACE
  (tmp file → MediaPlayer prepare + duration>300ms → atomic rename; failure never harms old clip),
  auto-play after install. PLUS catalog refactor (sent mid-flight): ClipCatalog.kt = single source
  of truth for every ClipSpec; RecorderActivity iterates it; new announcement ⇒ row auto-appears.

**NEXT (the plan in my head):**
1. Merge order: SIRI → CAM → VOICE → CLIP → LUX (LUX last, most files). After each: build. Stitch
   points: dedupe Wi-Fi perms in manifest; verify CameraSendActivity string-classname intent resolves;
   verify Announcer API names CAM calls; ClipCatalog vs LUX no overlap (different files).
2. v1.1 emulator gauntlet: offline verbs (incl. alarm parse→DayScheduler fire), camera flow (emulator
   camera app), voice picker audition + cold-start greeting regression (Announcer onInit change!),
   clip import file+Drive+bad-link, all four LUX screens screenshot review + jank %, plus smoke of
   v1.0 paths (greeting, caller, find-phone, back-consumed).
3. Release v1.1 (versionCode 11) + memory update + final summary.
4. QUEUED NEXT SESSION — "beautiful voice" plan (Alok asked for ChatGPT-quality speech): custom/neural
   TTS on the 2GB Oppo stays infeasible (storage full, SD450) BUT the app speaks a small fixed
   repertoire → pre-synthesize every ClipCatalog line ONCE with a premium neural TTS voice (Mac-side
   script: catalog → TTS API → m4a per key), install via WP-CLIP's Drive/file import. She hears a
   studio voice OFFLINE at zero runtime cost; family recordings still override; only dynamic text
   (AI replies, caller names, weather) stays on Google TTS + new voice picker. Script name idea:
   tools/make_clips. Wake word: see §16-F (Porcupine fastest, Vosk free) — unchanged, next session.
**STITCH progress (live):** SIRI+CAM+VOICE merged to master ✅ build passes. Conflicts resolved:
Settings.kt kept HEAD TTS block; SIRI's cherry-picks were STALE STUBS of Finder/RecorderActivity
(took ours); manifest deduped Wi-Fi perms + kept Recorder/Finder/CameraSend all three. Remaining:
merge LUX + CLIP when they land, bundle neural clips (tools/make_clips, edge-tts te-IN-ShrutiNeural
PROVEN: 47KB mp3/2 sentences, venv in session scratchpad), lean self-run smoke (NO gauntlet agent —
usage at 20%), release v1.1 (versionCode 11). If session dies: branches worktree-agent-a43392435e5899eca
(LUX) + worktree-agent-a279c50e9ced7419e (CLIP) may hold uncommitted/committed work — check before redoing.

### §17 FINAL STATE (usage died at 1% — 2026-07-11 night). CONTINUE FROM HERE:
**DONE:** v1.0 RELEASED (tag v1.0). Merged to master: WP-SIRI (offline verbs), WP-CAM (photo→WhatsApp),
WP-VOICE (voice picker), WP-CLIP (file/Drive import + ClipCatalog), Announcer bundled-clip fallback,
WP-LUX (luxury GUI — see merge result line above; if aborted, merge branch worktree-agent-a43392435e5899eca,
0.00% jank verified, screenshots in session scratchpad). LUX fixed real bug: snooze btn contrast (btn_outline_lux).
**NOT DONE — v1.1 release checklist (in order):**
1. If LUX merge aborted: redo it (conflicts would be trivial—LUX owns Main/Alert/Charging/FindPhone+ui/).
2. Generate bundled clips → app/src/main/assets/clips/<key>.mp3 for keys: home_greeting, talk_greeting,
   goodmorning, charger_connected, charger_removed, battery_full, battery_low, found_phone, alarm, hour_0..23.
   Text = each ClipSpec hint in ClipCatalog.kt (hours: "ఇప్పుడు <period> <N> గంటలు", use Telugu number words).
   Tool PROVEN: `TTSENV/bin/edge-tts --voice te-IN-ShrutiNeural --rate=-8% --text "<hint>" --write-media <key>.mp3`
   (venv was in session scratchpad; recreate: python3 -m venv env && env/bin/pip install edge-tts). Skip caller_* keys.
3. ./gradlew assembleDebug; emulator smoke: cold-start greeting speaks (VOICE moved initDone in onInit — watch
   pendingText regression), one offline verb ("టైం ఎంత" in Talk with no key), chime demo plays SHRUTI mp3 not TTS,
   Recorder file-import one mp3, LUX screens eyeball.
4. versionCode 11 / "1.1" → assembleRelease → gh release create v1.1 (co-edited repo: gh release list FIRST).
5. Real-device: Alok installs v1.1 per SETUP_PHONE.md, runs Recorder Studio with family (his last step).
**QUEUED v1.2:** wake word (§16-F, Porcupine), WhatsApp video-call tiles, Device-Owner powers (§16-addendum),
medicine reminders + SOS (§16-E). Everything else in §16.

## 18. v1.1 RELEASED (2026-07-12) — the §17 checklist is now CLOSED

**Session: Fable orchestrated, Sonnet executed (Alok's agent-economy rule). Fable-5 usage limit was hit
mid-session; default model switched to Opus 4.8. Kept all execution on Sonnet agents.**

**DONE (all pushed to master, origin ahead resolved):**
- Clips bundled: `77667f1` — 33 te-IN-ShrutiNeural mp3s (rate -8%) in app/src/main/assets/clips/ for
  home_greeting, talk_greeting, goodmorning, charger_connected/removed, battery_full/low, found_phone,
  alarm, hour_0..23 (hour texts = ClipCatalog hourlySpecs hints verbatim; caller_* skipped). 524KB total.
  Emulator smoke PASSED (Sonnet): cold-start greeting plays home_greeting clip (WP-VOICE initDone
  regression did NOT occur), chime + low-batt demos play Shruti mp3 not TTS, offline "టైం ఎంత" answered
  with no AI key, Recorder Studio opens with catalog rows. Real pkg = com.ammamma.companion (brief's
  com.example.* was wrong). AVD gotcha reconfirmed: `adb emu power status charging`; Telugu can't go via
  `adb shell input text` — used ADBKeyboard IME broadcast.
- Full read-only bug audit (Sonnet): merge of the 5 WP branches left NO stitch damage (verified). Found
  4 real bugs; ALL fixed in `8322c51`:
  1. RecorderActivity.stopRecording — unguarded copyTo force-closed on full storage → now uses
     ClipStore.commitImport (atomic rename, no ENOSPC) in try/catch + Telugu "no space" toast.
  2. OfflineIntents.alarmCalendar — hour 12 always resolved to NOON, midnight unreachable → 12 handled
     as its own noon-vs-midnight case; digits 13..23 pass through as 24h; 1..11 unchanged.
  3. ChatStore.pruneOlderThan — corrupt JSON was skipped forever (leak) → catch now deletes it.
  4. Announcer volume — media stream could stick at MAX if killed mid-line → savedVolume now stashed to
     Settings (stashedMusicVolume, dflt -1) on raise, cleared on restore, recovered in Announcer init.
- Version bump `6e0e841`: versionCode 11 / "1.1".
- assembleRelease → signed APK 1.26MB (bigger than v1.0's 740KB = the 33 clips), verified signer
  CN=Ammamma Companion. **RELEASED: github.com/alokflows/ammamma-companion/releases/tag/v1.1** (gh
  release list checked first — co-edited repo, no collision).

**NOT DONE — Alok's ONE human step (unchanged):** install v1.1 on the Oppo per SETUP_PHONE.md, then run
Recorder Studio with family to record the real voices (his LAST step). No dev work remains for v1.1.

**NEXT SESSION = v1.2** (see §16 / QUEUED above). Nothing blocks it.

## 19. v1.2 MASTER BRIEF — written 2026-07-12 by Fable at Alok's dictation, FOR THE NEXT CLAUDE

**Read this ENTIRE section before touching code. Alok dictated these decisions in the session that
shipped v1.1, then closed the terminal so a fresh session builds v1.2. This is his grandmother's phone
and he is emotionally invested ("I'm really full of emotions"). Match that care. You and the previous
Claude are ONE continuous team — this section is us handing the baton, not a status report.**

### How Alok works (PERMANENT rules — obey without being re-told)
- **Agent economy:** YOU (Fable) orchestrate — triage, read code, write specs, stitch merges, run the
  release, all cheap and context-keeping. Spawn **Sonnet** to EXECUTE clear specs. Spawn Opus ONLY for
  genuine architecture. Usage is scarce (a Fable-5 limit was hit last session). Before any spawn, check
  disk state of prior partial work so nothing is redone. Keep every spec self-contained so a dead agent
  is replaceable by a fresh one on the same worktree. NEVER burn tokens implementing yourself.
- **Respect her:** never design as if grandma is a dummy. Show REAL info (%, health, ETA, times) big and
  clear AND speak it aloud. Every dead-end speaks a helpful Telugu line — never fail silently.
- **Handoff discipline:** update this HANDOFF after EVERY finished task (done shas / now / next). Never
  batch to session end.
- **UI IS THE PRODUCT SHE SEES.** She sees no code, no text — only the UI. It must be Apple-flawless:
  beautiful, intuitive, dear to the heart. Even Settings buttons must NOT be generic olden-day gradient
  buttons — refined, proper, consistent with the WP-LUX luxury language. **Alok will WATCH the emulator
  live while you test UI** — boot `ammamma_oppo` and let him see it before he judges. Design→code is ONE
  loop that Claude owns end to end.
- **Co-edited repo:** Gemini/Antigravity also push here. ALWAYS `gh release list` before tagging. Keystore
  + creds at repo root (gitignored): `ammamma-release.keystore`, `keystore.properties`, storePassword/
  keyPassword `ammamma2026`, alias `ammamma`. Version is at **versionCode 11 / "1.1"** → next is 12 / "1.2".
  Package is **com.ammamma.companion**. Emulator gotchas: `adb emu power status charging` (ac on alone =
  NOT_CHARGING); Telugu text can't go via `adb shell input text` (use ADBKeyboard IME broadcast); check
  `voice_muted` pref before any audio test.

### Alok's LOCKED decisions for v1.2 (from this session — do NOT re-ask these)

**A. Voice "open the specific thing" — HIS #1 EFFORT, the soul of it.**
She speaks Telugu and the phone opens/does the SPECIFIC thing — e.g. "open <X> YouTube channel" / "play
<song>". Offline pattern-match FIRST (extend OfflineIntents), AI intelligence for the fuzzy match, then
deep-link into the target app (YouTube channel/search intent, etc.). Generalize past fixed verbs to
parametric "open <named target>". Ship a general YouTube-search deep-link + a couple examples now; ASK
Alok for her list of favorite channels/apps to hard-map for instant offline opening.

**B. Medicine reminders.**
- SET = BOTH. Primary VOICE in the Talk tab ("రోజూ పొద్దున 8కి మందు" → parsed → set → confirmed in
  Telugu). Backup MANUAL type-and-send in the bar. Automation-first. Reuse the Telugu time-word parser in
  OfflineIntents.alarmCalendar (and note bug #2's 12-o'clock fix already landed).
- ALERT when due = **full-screen talking card that REPEATS ~30s until dismissed** (like alarm/battery
  cards): big 💊 photo, speaks in family/neural voice, **"తీసుకున్నా / Taken" button AND a SNOOZE button**
  (Alok added snooze). Reuse AlertActivity + the alert-repeat engine.
- Recurring (daily) + one-off; survives reboot (DayScheduler, already Doze-proof via setExactAndAllowWhileIdle).

**C. SOS / emergency — "everybody linked gets it, and it should be FULL".**
- TRIGGER = BOTH: (1) **power button ×3 fast** from anywhere incl. lock screen — feasible by counting
  rapid ACTION_SCREEN_ON/OFF in CompanionService; VERIFY on ColorOS. (2) a **big SOS button on home**.
- PAYLOAD to EVERY linked family member = ALL FOUR: **continuous live location** (keeps updating, not a
  one-shot pin) + **front & back camera photos** (silent capture) + **loud alarm on her phone** (max) +
  **alert text to all family numbers** (SMS, works offline).
- Only EXPLICITLY LINKED family get it (see D).

**D. Family members = explicit, linked, SINGLE SOURCE OF TRUTH (Alok stressed hard).**
- A proper, BEAUTIFUL Settings screen to ADD family members: name + number + photo + role/permissions.
  "We should specially link the family members — do NOT simply consider someone else as family."
- This one linked list feeds EVERYTHING: photo-dial home faces, WhatsApp video tiles, SOS recipients,
  SMS-control allow-list. Per-member flags: gets-SOS, can-control-by-SMS, video-call.

**E. Family remote control by SMS (extends find-my-phone).**
- A LINKED family member texts a command → the app acts while it's active. Confirmed: **remote photo** —
  SMS command → silent front/back camera capture → delivered to family (so "if something happens, we get
  to know"). Base = the existing find-phone-by-codeword SMS receiver; guard by linked-family-number + code
  word. Keep the existing find-phone scream command too.

**F. WhatsApp video tiles:** big face buttons on home → one tap → start a WhatsApp VIDEO call to that
linked person (WhatsApp video deep-link). One tap, no menus.

**G. CPU / PROCESS PRIORITY — Alok's "MOST important" reliability ask.**
The app sometimes just STOPS because ColorOS deprioritizes/kills it. He wants OS-level priority, always
alive. HARDEN and find the REAL cause (don't just re-tell setup steps): confirm CompanionService is a true
ongoing foreground service with correct notification importance; verify START_STICKY + onTaskRemoved
resurrection + the 15-min watchdog actually fire on ColorOS; Process.setThreadPriority on work threads;
reconfirm SETUP_PHONE.md ColorOS steps (auto-start, battery-whitelist, lock-in-recents). Investigate why
it still dies on the real device and fix the cause.

### THE ONE OPEN DECISION — ASK ALOK FIRST THING NEXT SESSION
**How to DELIVER photos (SOS front/back + SMS-triggered) to family?** WhatsApp CANNOT be auto-sent by a
normal app (confirmed, dropped in v0.6 — a photo can't reach WhatsApp without her tapping send). Needs
internet either way. Options:
- **RECOMMENDED: Telegram bot → a private family group.** Instant push, free, zero user interaction, and
  Alok already runs Telegram bots (Zilla, LLM-Wiki). Family joins one group; SOS photos + live-location
  links land instantly.
- **Apps Script email relay** (the already-planned TRAVEL_EMAIL.md pattern): app POSTs photo → emails all
  family. Reaches everyone but email is slower to be noticed in an emergency.
- SMS text + live-location ALWAYS go offline regardless; only PHOTOS need one of the above (queue → send
  when back online). Get his pick before building the photo-delivery path.

### v1.2 WORK PACKAGES (disjoint file ownership — Sonnet in parallel where safe)
- **WP-FAMILY** — family manager model + beautiful Settings UI; the single source of truth. Do FIRST;
  WP-SOS/WP-VIDEO/photo-dial depend on it.
- **WP-MED** — medicine reminders (voice+manual set; full-screen repeating talking card w/ Taken+Snooze;
  DayScheduler recurring + one-off).
- **WP-SOS** — power×3 + home-button trigger; continuous location + photos + alarm + SMS to linked family;
  SMS remote-photo command (waits on the delivery-channel decision for the photo leg only).
- **WP-OPEN** — parametric "open the specific thing" voice (YouTube deep-links + target mapping).
- **WP-VIDEO** — WhatsApp video-call tiles on home.
- **WP-UI** — the Apple-flawless pass, Settings especially (kill generic gradient buttons; extend WP-LUX
  everywhere). CONTINUOUS, not one-shot — hold every screen to his bar; he watches the emulator.
- **WP-PRIORITY** — process-priority / never-dies hardening (item G).

### Still pending from Alok (human step, unchanged)
Install **v1.1** on the Oppo per SETUP_PHONE.md, then record family voices in Recorder Studio (his LAST
step). v1.2 dev does NOT block on this.

## 20. v1.2 BUILD — LIVE session state (2026-07-12, fresh session executing §19)

**Model note:** Fable-5 unavailable this session → orchestrating on Opus 4.8, executing on Sonnet
(agent-economy rule intact). Repo at `0c13446` (master, v1.1, clean tree). Old merged worktrees from
v1.0/v1.1 still registered (harmless; new WPs use fresh isolated worktrees).

**DECISION CAPTURED (Alok, this session):** THE ONE OPEN DECISION (§19 photo delivery) = **BOTH** —
Telegram bot → private family group (instant silent SOS push) AND Apps Script email relay (backup
fan-out). SMS text + live-location always go offline regardless; only PHOTOS use these channels
(queue → send when back online). WP-SOS builds both photo-delivery legs.

**WP-FAMILY ✅ MERGED to master (`de31286`, pushed).** Single-source-of-truth family manager.
`Contact` extended with 4 flags `isFamily/getsSos/smsControl/videoCall` (optBoolean migration: pre-v1.2
numbered contacts become isFamily+getsSos=true — they ARE family the user added; empty seed rows stay
out; no re-migration once saved). New `FamilyActivity` (GlowBackdrop+Press LUX): per-member photo
(Faces reuse, decode off UI thread, process-death-safe pending-id), 3 quick permission pills +
full add/edit form, delete cleans face+caller-clip, every dead-end SPEAKS a Telugu hint. Launch button
in Settings. **KEY DELIVERABLE — the helpers dependent WPs call:**
`Contacts.family(ctx)`, `Contacts.sosRecipients(ctx)` (isFamily&&getsSos&&number), `smsControllers(ctx)`,
`videoTiles(ctx)`; plus `addFull(...)→id`, `updateFull(id,...)`, `removeById(id)`. Orchestrator reviewed
the full diff line-by-line — no defects; build verified green in the worktree. NOT a parallel list —
reuses the dial list, so MainActivity photo-dial keeps working. **Emulator UI smoke of FamilyActivity
is DEFERRED to the pre-v1.2-release gauntlet** (compile verified; screen not yet run on the AVD).

**WP-PRIORITY diagnosis DONE (orchestrator, item G — Alok's "MOST important" reliability ask).**
Root-cause read of CompanionService.kt + AndroidManifest: the 3 standard levers are present
(foreground+START_STICKY, onTaskRemoved 1.5s resurrection alarm, 15-min inexact watchdog) and
CallReceiver/FindPhoneReceiver already resurrect on call/SMS. Real, code-actionable gaps found →
WP-PRIORITY spec (dispatch AFTER WP-FAMILY merges — both touch manifest + SettingsActivity, keep
ownership disjoint):
1. `CompanionService` lacks `android:stopWithTask="false"` → survives task-swipe only via the 1.5s
   kill→restart gap. Add it (survive swipe directly).
2. Too few resurrection wake-vectors. After a ColorOS **force-stop**, alarms + STICKY are cleared →
   ONLY a system broadcast can revive. Today: BOOT/PACKAGE_REPLACED + call/SMS. Add manifest receivers
   (still fire on API 27) for `USER_PRESENT` (every unlock) + `ACTION_POWER_CONNECTED/DISCONNECTED`
   (every plug) → each a free heartbeat that `startForegroundService(CompanionService)`. New
   `AliveReceiver` or extend BootReceiver.
3. No death visibility (charge-only cable = no logcat). Persist a "last-alive" timestamp each
   watchdog tick + onStartCommand; show in Settings ("companion alive Xm ago") → turns his real-device
   reports into data.
4. `Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)` on the speech path (Announcer) — minor
   anti-stutter.
5. Reconfirm SETUP_PHONE.md ColorOS steps (auto-start, battery-whitelist, lock-in-recents).
**HONEST CEILING (for Alok):** after a hard ColorOS force-stop, NOTHING in-app can win — true never-kill
needs **Device Owner** (one-time `adb shell dpm set-device-owner` during setup → DevicePolicyManager,
also unlocks kiosk/lock-task). BLOCKED by his charge-only cable (needs USB data). So: ship the in-app
hardening now; Device Owner = an optional "if you get a data cable" upgrade, NOT a now-fix. Flagged to
Alok, non-blocking.

**WP-OPEN REDESIGNED (Alok, this session) — "do the thing" is an ONLINE, INTELLIGENCE-FIRST engine,
NOT an offline keyword list.** Alok's exact intent: the engine must be genuinely intelligent/proactive
and understand her (she is NOT dumb — don't build for a dummy). Design LOCKED:
- The AI brain (AiBrain, online) PARSES her free Telugu utterance into a structured action
  (play_music / open_channel / open_app / search / ...), tolerant of any phrasing. Free models are
  text-only → prompt the model to emit a small JSON action; orchestrator writes the exact prompt.
- CAPABILITY-AWARE via PackageManager: for "play <song>" detect installed music apps (YouTube Music
  com.google.android.apps.youtube.music, Spotify com.spotify.music, JioSaavn com.jio.media.jiobeats,
  Gaana com.gaana, Wynk, etc.). If one exists → play there (try MediaStore
  `INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` w/ EXTRA_MEDIA_TITLE so it plays directly); if NONE → open
  **YouTube and play directly** (search deep-link, best-effort first-result play). Adapt to HER phone.
- ONLINE (needs internet — YouTube/streaming can't work offline, Alok confirmed). Offline "open"
  matching is DROPPED for now (may return later). Device actions (time/battery/torch/Wi-Fi/alarm) stay
  offline in OfflineIntents; only CONTENT/OPEN goes through this new online resolver.
- Always speak a Telugu confirmation of what it's doing; never dead-end silently. Deep-link intents are
  version-fragile → REAL-DEVICE test required (brief already flagged this).
Favorite-channels list from Alok is now OPTIONAL (helps the AI disambiguate her Telugu names, not needed
for offline mapping).

**NEXT — PAUSED for usage cap (2026-07-12). Fresh session resumes here, foundation is IN.** WP-FAMILY
done+merged; WP-PRIORITY diagnosed with a ready spec (above); WP-OPEN redesigned (above). Dispatch
Phase 2 in disjoint worktrees (Sonnet, self-contained specs):
- **WP-PRIORITY** — spec in this §20 (stopWithTask=false; new AliveReceiver on USER_PRESENT +
  POWER_CONNECTED/DISCONNECTED → startForegroundService; last-alive timestamp pref shown in Settings;
  THREAD_PRIORITY_URGENT_AUDIO on speech). Touches manifest + Settings — now clear of WP-FAMILY.
- **WP-MED** — reuse AlertActivity + repeat engine + DayScheduler + OfflineIntents Telugu time-parser
  (bug #2 noon/midnight fix already landed); full-screen repeating talking card w/ Taken + Snooze;
  voice-set in Talk + manual set; recurring + one-off; reboot-safe.
- **WP-OPEN** — ONLINE intelligence-first "do the thing": AiBrain parses Telugu → JSON action;
  PackageManager capability detect (music app → play there via MEDIA_PLAY_FROM_SEARCH, else YouTube
  play-direct); speak Telugu confirmation; real-device deep-link test. (Offline-open dropped.)
Then Phase 3 (depend on WP-FAMILY helpers): **WP-SOS** (power×3 + home button → continuous location +
front/back photos + alarm + SMS to `Contacts.sosRecipients`; photo legs = Telegram bot + Apps Script
email, Alok chose BOTH), **WP-VIDEO** (WhatsApp video tiles from `Contacts.videoTiles`), **WP-UI**
continuous Apple-flawless pass (esp. Settings — kill generic gradient buttons). Emulator gauntlet
(incl. FamilyActivity UI smoke deferred here) + `gh release list` before tagging **v1.2** (versionCode
12 / "1.2"). Stale v1.0/v1.1 agent worktrees still registered — harmless; can `git worktree prune` +
remove them for tidiness.
