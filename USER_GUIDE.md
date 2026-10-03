# SMS Gateway — User Guide

This guide is for **using** the app on a phone. Developers should also read the
[README](README.md) for build instructions, the full API reference and remote-access setup.

The app turns an Android phone into an SMS gateway: your backend (or a curl command) calls a small
HTTP API on the phone, the phone sends the SMS, and the result is stored in a local history you can
review later.

**Requirements:** Android 6.0+ (minSdk 23), a SIM with SMS capability, and a network your caller can
reach (same Wi-Fi, VPN or a tunnel).

---

## 1. Install

The APK is sideloaded (it is not on Google Play — the SMS permissions are restricted there).

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the `.apk` to the phone and open it (allow "Install unknown apps" for your file manager /
browser when prompted).

## 2. Grant permissions (first launch)

On first launch the app asks for four permissions. All of them are required:

| Permission | Why |
|---|---|
| `SEND_SMS` | actually send messages |
| `READ_SMS`, `RECEIVE_SMS` | detect send/delivery status of outgoing messages |
| `POST_NOTIFICATIONS` (Android 13+) | show the persistent server notification and SMS status alerts |

**If the SMS permission toggles are greyed out** (this always happens with a sideloaded APK on
Android 13+), unlock them once:

1. Open *Settings → Apps → SMS Gateway → ⋮ (top right) → Allow restricted settings*
2. Go back and grant the permissions.

You can re-open the prompts any time from the **Server** tab → *Permissions needed* card →
**Grant permissions**.

## 3. Make it survive (battery optimisation)

Android kills background servers aggressively. Before relying on the gateway:

1. In the app's **Settings tab → Reliability**, tap **Battery settings** and exempt this app from
   battery optimisation ("Allow background activity" / "Unrestricted").
2. On Xiaomi, Oppo, Vivo, Realme, Samsung etc. also enable **autostart** / "No restrictions" in the
   vendor battery settings, and lock the app in the recents screen.
3. Keep the phone **plugged in and on a charger** — a gateway that is always on is the realistic
   deployment.
4. The server does **not** auto-start after a reboot. Open the app once after rebooting
   (the master switch stays on, so opening the app restarts the server).

> **Android 15+ / 6-hour limit:** the server runs as a `dataSync` foreground service, which Android
> limits to roughly 6 hours per 24 hours. When that happens you get a *"SMS Gateway stopped"*
> notification ("Tap to restart"); tapping it (or opening the app) starts the server again. For a
> true 24/7 gateway see the
> [README's Android 15 note](README.md#android-15-note-datasync-foreground-service).

## 4. The Server tab

This is the home screen (bottom bar: **◉ Server · ☰ History · ⚙ Settings**).

### Status and master switch

The badge shows the server state: `RUNNING on port 8080`, `STARTING…`, `ERROR` (with the error text
below it) or `STOPPED`. Flip the **Server** switch to start/stop the embedded HTTP server.

### Port

Enter a port between 1024 and 65535 (default **8080**) and press **Apply**. If the server is
running it restarts on the new port immediately. The port must be free on the device.

### API key

A 64-character hex key (32 random bytes) is generated on first launch and is required for every API
call except `/api/info`.

* **Show / Hide** — reveal or mask it (it is masked by default).
* **Copy** — copy it to the clipboard for your backend's environment variables.
* **Regenerate** — invalidates the old key **immediately**; update every client afterwards.

### Device address

Shows the phone's local IP(s) and the ready-to-use **Base URL**, e.g.
`http://192.168.1.50:8080/api`. Press **Copy base URL** to put it on the clipboard.
If *External domain mode* is enabled in Settings, this card shows your public HTTPS URL instead.

> Phone and caller must be able to reach each other: same Wi-Fi is the simple case. For access from
> the internet use a Cloudflare tunnel or an Nginx reverse proxy ([README](README.md#remote-access-from-the-cloud)).

### Test send

A one-tap end-to-end check that calls the phone's **own** API (`POST /api/send`) — so it exercises
the whole stack (HTTP → auth → rate limit → SMS).

1. Server must be `RUNNING` (the button is disabled otherwise).
2. Enter the number in strict **E.164** format — `+919876543210`, not `9876543210`.
3. Press **Send test SMS** — this sends a **real SMS** from your SIM.
4. The raw JSON response (HTTP status + pretty-printed body) appears below the button.

The same response shape is what your backend receives; see the API section of the README.

## 5. The History tab

Every message the gateway accepted is stored locally (including the full text — see the security
note below).

* **Filter chips** — `All`, `PENDING`, `SENT`, `DELIVERED`, `FAILED`, `UNKNOWN`.
* **Tap a row** — full detail: phone number, status, created/delivered times, `request_id`,
  SIM subscription id, error message and the complete message text.
* **Purge old** — deletes messages older than the retention period set in Settings.
* **Delete all** — wipes the whole history (confirmation required).

| Status | Meaning |
|---|---|
| `PENDING` | handed to the Android SMS app, not yet accepted by the radio |
| `SENT` | the radio accepted every part of the message |
| `DELIVERED` | the carrier reported delivery |
| `FAILED` | the phone or carrier rejected it (see the error in the detail view) |
| `UNKNOWN` | stuck in `PENDING` for over 10 minutes (usually the app was killed) |

Many Indian carriers/SIMs never return delivery reports, so `SENT` is often the final status — that
does **not** mean the SMS failed.

## 6. The Settings tab

### Rate limiting (default: on, 10/minute, 100/hour)

Counts SMS sends per API key; each message inside a bulk request counts once. A request that would
exceed either limit is rejected as a whole with HTTP 429 (`RATE_LIMIT_EXCEEDED`). Raise the numbers,
or switch the toggle off for a private gateway.

### History cleanup (default: on, 30 days)

Old messages are deleted automatically after the retention period. Message text includes OTP codes —
a shorter retention is safer. *Purge old* / *Delete all* on the History tab do the same manually.

### Notifications (default: on)

* The persistent **"SMS Gateway running"** notification is always shown while the server runs
  (that is what keeps the service alive). Tap it to open the app.
* With the toggle on, you also get an alert per status change: *"SMS #12 SENT — To …3210"*
  (the number is masked to the last four digits).

If notifications never appear, grant `POST_NOTIFICATIONS` and check the app's notification channels
(*SMS Gateway* and *SMS Activity*).

### External domain mode (default: off)

If you published the phone behind a tunnel (e.g. `https://sms.example.com`), enable this and enter
the URL so the Server tab shows the public base URL instead of the LAN IP. **Display only** — the
tunnel itself is configured outside the app ([README](README.md#remote-access-from-the-cloud)).

### Reliability

A shortcut to Android's battery-optimisation settings; see section 3.

## 7. First message from your computer

1. On the phone: Server tab → **Copy base URL** and **Copy** (API key). Keep the server switched on.
2. On your computer (same network):

```bash
BASE=http://192.168.1.50:8080/api
KEY=<pasted-key>

curl -X POST $BASE/send \
  -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"phone_number":"+919876543210","message":"Your verification code is 482915.","request_id":"otp-1"}'

curl -H "X-API-Key: $KEY" "$BASE/status?request_id=otp-1"
```

The README contains the complete API reference, more examples (bulk, history, Node.js) and the
`scripts/smoke-test.sh` script.

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| Permission toggles are greyed out | *Settings → Apps → SMS Gateway → ⋮ → Allow restricted settings* (section 2) |
| `Connection refused` / timeout | Server switch on? Same Wi-Fi as the phone? Correct IP and port? Phone screen on the Server tab shows the live Base URL |
| HTTP 401 `AUTHENTICATION_FAILED` | Wrong key, or it was regenerated — copy the current key again |
| HTTP 429 `RATE_LIMIT_EXCEEDED` | Hit the rate limit; wait for `Retry-After`, or raise/disable it in Settings |
| HTTP 400 `INVALID_REQUEST` | Number is not strict E.164 — must be `+` followed by 8–15 digits, no spaces |
| Server stops after a few hours | Battery kill or the Android 15 6-hour limit — section 3; watch for the "SMS Gateway stopped" notification |
| Server dies after reboot | Normal: no auto-start on boot — open the app once after a reboot |
| Status stuck `PENDING`, then `UNKNOWN` | The app was killed while the SMS was in flight; check battery settings |
| Stays `SENT`, never `DELIVERED` | Your carrier usually doesn't send delivery reports — expected on many Indian SIMs |
| `SMS_SEND_FAILED` immediately | Check SMS permission, SIM active with balance, and that the phone can send SMS manually. In India, commercial/A2P OTP traffic needs DLT registration — fine for low-volume testing on a personal SIM |
| No notifications at all | Grant `POST_NOTIFICATIONS`; check the *SMS Gateway* / *SMS Activity* channels aren't disabled |
| API not reachable from the internet | The phone is behind NAT — use a Cloudflare tunnel or Nginx reverse proxy ([README](README.md#remote-access-from-the-cloud)); don't port-forward plain HTTP |

## 9. Security notes (short version)

* The API key is the only credential; treat it like a password. Regenerate it if a client may have
  leaked it.
* History stores full message text (OTP codes included) — keep retention short or delete history.
* Notifications only ever show the last four digits of a number.
* On a LAN everything is plain HTTP. Anything crossing the internet must go through an HTTPS
  tunnel/proxy ([README](README.md#remote-access-from-the-cloud)).
* Android caps how many SMS an app may send in a short period (~30 per 30 minutes on many versions),
  and carriers filter bulk/commercial traffic — keep volume low and check your carrier's terms.
