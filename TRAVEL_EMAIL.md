# TRAVEL_EMAIL.md — future email channel for Travel mode (design only, not built)

Travel mode today sends an SMS breadcrumb (battery %, charger state, maps link)
whenever the charger is plugged in or out — see `LocationReplyService.kt`. This
document sketches a SECOND channel: email, with photos attached, via a free
Google Apps Script relay. **Nothing here is built yet.** The Settings screen
shows the Email row disabled ("coming soon") until this exists.

Why not send email straight from the phone? Android has no built-in "send an
email with attachments silently, no app chooser" API that works offline-first
like SmsManager does. The simplest reliable path is: phone → HTTP POST → a tiny
script that already lives inside Google's infrastructure and can send Gmail for
free, with no server to maintain.

## How it would work

1. The phone already has the battery %, charger state, GPS location, and (in
   Travel mode) up to two theft-guard photos in `filesDir/theft`.
2. Instead of (or in addition to) the SMS, the phone HTTP-POSTs a small JSON
   body to a private Apps Script "web app" URL, which is just a normal
   Settings string (like the AI API key is today):

   ```json
   {
     "battery": 63,
     "charging": "plugged IN",
     "location": { "lat": 17.4239, "lng": 78.4738 },
     "photos": [
       { "name": "front.jpg", "base64": "<~50KB of base64 JPEG>" },
       { "name": "back.jpg",  "base64": "<~50KB of base64 JPEG>" }
     ]
   }
   ```

   Photos are kept small (~50KB each, already the theft-guard's low-res
   640×480 capture) so the POST stays fast on a weak connection and the whole
   thing fits comfortably under Apps Script's request-size limits.

3. The Apps Script receives the POST, builds an email (maps link from the
   lat/lng, battery line, photos as attachments) and sends it via
   `MailApp.sendEmail(...)` to the family's addresses — which the script owns,
   not the phone, so no email server config ever touches the app.
4. The script replies with a plain "OK" or an error string; the phone logs the
   result the same way LocationReplyService logs SMS failures. No retry loop
   needed at first — a missed email during travel is not safety-critical the
   way find-my-phone is.

## Deploy steps (for whoever sets this up — tap by tap)

1. Go to **script.google.com** on the family's Google account (a personal
   Gmail is fine — this is what `MailApp.sendEmail` will send FROM).
2. **New project** (top-left, "+ New project").
3. Delete the placeholder `myFunction() {}` and paste the script below.
4. Rename the project (top-left, click "Untitled project") to something like
   `AmmammaTravelRelay` so it's recognizable later.
5. Click **Deploy → New deployment**.
6. Click the gear icon next to "Select type" → choose **Web app**.
7. Fill in:
   - Description: `travel relay v1`
   - **Execute as: Me** (your account — so it can send mail as you, not the
     unauthenticated caller)
   - **Who has access: Anyone with the link** (needed so the phone, which
     isn't logged into Google, can reach it — this is why the URL itself must
     stay private, like an API key)
8. Click **Deploy**. The first time, Google will ask you to authorize the
   script (it needs permission to send email as you) — click through the
   "unsafe app" warning (it's your own script) and **Allow**.
9. Copy the **Web app URL** it gives you (ends in `/exec`). This is the secret
   that goes into the phone's Settings screen once the Email channel is built
   — treat it like a password; anyone with the URL could trigger an email send.
10. Test it by pasting the URL into a browser or `curl -X POST` with the JSON
    above (see `doPost` below) — check the family inbox for the test email.

## Sketch of the Apps Script code

```javascript
// Code.gs — receives the phone's POST and emails the family.
const FAMILY_EMAILS = ["daughter@example.com", "son@example.com"]; // edit this

function doPost(e) {
  try {
    const data = JSON.parse(e.postData.contents);

    const mapsLink = data.location
      ? "https://maps.google.com/?q=" + data.location.lat + "," + data.location.lng
      : "(no location)";

    const body =
      "Battery: " + data.battery + "%\n" +
      "Charger: " + data.charging + "\n" +
      "Location: " + mapsLink;

    const attachments = (data.photos || []).map(function (p) {
      return Utilities.newBlob(
        Utilities.base64Decode(p.base64),
        "image/jpeg",
        p.name
      );
    });

    MailApp.sendEmail({
      to: FAMILY_EMAILS.join(","),
      subject: "Ammamma travel update",
      body: body,
      attachments: attachments
    });

    return ContentService
      .createTextOutput("OK")
      .setMimeType(ContentService.MimeType.TEXT);
  } catch (err) {
    return ContentService
      .createTextOutput("ERROR: " + err)
      .setMimeType(ContentService.MimeType.TEXT);
  }
}
```

## What building the real channel would need (not done yet)

- A `Settings.travelEmailUrl` string (same pattern as the AI key field).
- A small HTTP POST helper in `LocationReplyService` (or a sibling service) —
  the app has no networking library today, so this would use
  `java.net.HttpURLConnection` directly (no new dependency, matching the
  project's "no new libraries" rule).
- Wiring `Settings.travelEmailEnabled` (already in `Settings.kt`) to actually
  fire this POST alongside/instead of the SMS.
- Turning the Email switch in Settings from disabled to live once the above
  exists.

No WhatsApp channel is planned — SMS (built) and email (this doc) cover it.
