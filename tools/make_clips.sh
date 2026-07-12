#!/bin/bash
# Pre-synthesize every fixed announcement as a studio-quality Telugu mp3 so the
# phone speaks with a neural voice OFFLINE at zero runtime cost. Output lands in
# app/src/main/assets/clips/<key>.mp3 — Announcer plays these between family
# recordings (filesDir/clips) and live TTS. caller_* keys are deliberately
# absent: those are the family's own voices, recorded in Recorder Studio.
#
# Texts mirror ClipCatalog.kt hints; hour lines use Telugu number words because
# the neural voice reads words more naturally than digits. If a hint changes in
# ClipCatalog.kt, change it here too and re-run.
#
# Usage (macOS or Linux, needs internet to Microsoft's Edge TTS endpoint):
#   ./tools/make_clips.sh
# Re-runs skip clips that already exist and look valid; delete a file to redo it.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=app/src/main/assets/clips
VENV=tools/.ttsenv
VOICE=te-IN-ShrutiNeural
RATE=-8%
MIN_BYTES=5000   # a real ~2s neural mp3 is 15-60 KB; smaller = failed synthesis

[ -d "$VENV" ] || python3 -m venv "$VENV"
"$VENV/bin/pip" show edge-tts >/dev/null 2>&1 || "$VENV/bin/pip" install -q edge-tts
mkdir -p "$OUT"

CLIPS='home_greeting|నమస్తే అమ్మమ్మ
talk_greeting|చెప్పమ్మా, ఏం కావాలి
goodmorning|శుభోదయం అమ్మమ్మ
charger_connected|ఛార్జర్ పెట్టారు
charger_removed|ఛార్జర్ తీసేశారు
battery_full|ఛార్జింగ్ నిండింది, ఛార్జర్ తీసేయండి
battery_low|ఛార్జ్ తక్కువగా ఉంది, దయచేసి ఛార్జ్ చేయండి
found_phone|ఫోన్ ఇక్కడ ఉంది అమ్మమ్మ!
alarm|అమ్మమ్మ, సమయం అయింది
hour_0|ఇప్పుడు రాత్రి పన్నెండు గంటలు
hour_1|ఇప్పుడు రాత్రి ఒంటి గంట
hour_2|ఇప్పుడు రాత్రి రెండు గంటలు
hour_3|ఇప్పుడు రాత్రి మూడు గంటలు
hour_4|ఇప్పుడు తెల్లవారుజాము నాలుగు గంటలు
hour_5|ఇప్పుడు తెల్లవారుజాము ఐదు గంటలు
hour_6|ఇప్పుడు ఉదయం ఆరు గంటలు
hour_7|ఇప్పుడు ఉదయం ఏడు గంటలు
hour_8|ఇప్పుడు ఉదయం ఎనిమిది గంటలు
hour_9|ఇప్పుడు ఉదయం తొమ్మిది గంటలు
hour_10|ఇప్పుడు ఉదయం పది గంటలు
hour_11|ఇప్పుడు ఉదయం పదకొండు గంటలు
hour_12|ఇప్పుడు మధ్యాహ్నం పన్నెండు గంటలు
hour_13|ఇప్పుడు మధ్యాహ్నం ఒంటి గంట
hour_14|ఇప్పుడు మధ్యాహ్నం రెండు గంటలు
hour_15|ఇప్పుడు మధ్యాహ్నం మూడు గంటలు
hour_16|ఇప్పుడు సాయంత్రం నాలుగు గంటలు
hour_17|ఇప్పుడు సాయంత్రం ఐదు గంటలు
hour_18|ఇప్పుడు సాయంత్రం ఆరు గంటలు
hour_19|ఇప్పుడు రాత్రి ఏడు గంటలు
hour_20|ఇప్పుడు రాత్రి ఎనిమిది గంటలు
hour_21|ఇప్పుడు రాత్రి తొమ్మిది గంటలు
hour_22|ఇప్పుడు రాత్రి పది గంటలు
hour_23|ఇప్పుడు రాత్రి పదకొండు గంటలు'

fail=0
while IFS='|' read -r key text; do
    f="$OUT/$key.mp3"
    if [ -f "$f" ] && [ "$(wc -c < "$f")" -ge "$MIN_BYTES" ]; then
        echo "skip  $key (exists)"
        continue
    fi
    echo "make  $key"
    if ! "$VENV/bin/edge-tts" --voice "$VOICE" --rate="$RATE" \
            --text "$text" --write-media "$f"; then
        echo "RETRY $key"; sleep 2
        "$VENV/bin/edge-tts" --voice "$VOICE" --rate="$RATE" \
            --text "$text" --write-media "$f" || { echo "FAIL  $key"; fail=1; continue; }
    fi
    if [ "$(wc -c < "$f")" -lt "$MIN_BYTES" ]; then
        echo "FAIL  $key (file too small — synthesis silently failed)"; rm -f "$f"; fail=1
    fi
    sleep 1   # be gentle with the free endpoint
done <<< "$CLIPS"

echo
ls -la "$OUT" 2>/dev/null || true
n=$(ls "$OUT"/*.mp3 2>/dev/null | wc -l | tr -d ' ')
echo "clips present: $n / 33"
[ "$fail" -eq 0 ] && [ "$n" -eq 33 ] && echo "ALL GOOD — rebuild the APK to bundle them." || echo "Some clips missing — re-run this script."
