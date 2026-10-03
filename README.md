# SMS Gateway (Android)

Turns an Android phone into a self-hosted SMS gateway with a small REST API.
Built for a Node.js backend that sends 6-digit OTP SMS to Indian (`+91`) numbers, but it accepts any E.164 number.

* Kotlin, Jetpack Compose (Material 3), minSdk 23, targetSdk 35
* Embedded HTTP server: NanoHTTPD, bound to `0.0.0.0`, run from a foreground service
* Room (SQLite) history, Gson, coroutines
* Everything is written from scratch; behaviour follows the spec in the task description.

## Documentation

| Document | For whom |
|---|---|
| **[USER_GUIDE.md](USER_GUIDE.md)** | *Start here if you just want to use the app*: install, permissions, app walkthrough (Server / History / Settings tabs), first message from a computer, troubleshooting |
| This README | Developers: build instructions, full API reference, smoke test, remote access, security notes |

## Build

Requirements: **JDK 17** and the **Android SDK** (platform 35). Android Studio (Koala or newer) has both.

```bash
# point Gradle at your SDK if ANDROID_HOME is not set
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first build downloads Gradle 8.9 and the dependencies, so it needs internet access.
Alternatively open the folder in Android Studio and press Run.

## First run and permissions

Quickstart — the full walkthrough with status meanings and a troubleshooting table is in
[USER_GUIDE.md](USER_GUIDE.md).

1. Open the app and accept the permission prompts: `SEND_SMS`, `READ_SMS`, `RECEIVE_SMS`, and `POST_NOTIFICATIONS` (Android 13+).
2. **Sideloaded APK on Android 13+:** SMS permissions are "restricted settings". If the grant buttons are greyed out,
   go to *Settings → Apps → SMS Gateway → ⋮ → Allow restricted settings*, then grant the permissions.
3. In *Settings → Reliability* exempt the app from battery optimisation (on Xiaomi, Oppo, Vivo, Samsung etc. also enable
   "autostart" / "no restrictions" in the vendor battery settings), otherwise the OS will kill the server.
4. On the **Server** tab: pick a port (default 8080), flip the master switch, and copy the API key.
5. Put the phone on a network your backend can reach (same Wi-Fi, VPN, or a tunnel, see below).

`READ_SMS` / `RECEIVE_SMS` are requested as specified but the current API does not read incoming messages.
Google Play restricts these permissions; this app is intended for sideloading.

### Android 15 note (dataSync foreground service)

The service uses `foregroundServiceType="dataSync"` as specified. Android 15 limits such services to roughly
**6 hours per 24 hours** and then stops them. The app posts a "SMS Gateway stopped" notification; opening the app restarts the server.
For a 24/7 gateway change the type to `specialUse` (with the matching `FOREGROUND_SERVICE_SPECIAL_USE`
permission and `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`) in `AndroidManifest.xml` and `GatewayService.kt`; that is fine for sideloaded builds but is reviewed on Google Play.
A phone that is always plugged in and on a charger is the realistic deployment.

## API

Base URL: `http://<device-ip>:<port>/api`. JSON in and out, CORS open to all origins.
Authenticate every endpoint except `/api/info` with either header:

```
X-API-Key: <key>
Authorization: Bearer <key>
```

| Method | Path | Notes |
|---|---|---|
| GET | `/api/info` | no auth; `{app_name, version, status, endpoints[], server_time}` (`server_time` is ISO-8601 UTC) |
| POST | `/api/send` | `{phone_number, message, request_id?, simSubscriptionId?}` |
| POST | `/api/send-bulk` | `{messages:[{phone_number, message, request_id?}], ...}` (1-100 items) |
| GET | `/api/status?request_id=<id>` or `?sms_id=<id>` | `{success, data:{...record}}` |
| GET | `/api/history?limit=50&offset=0&status=&phone_number=` | `{success, data[], total_count, limit, offset}` |
| GET | `/api/config` | `{server_port, server_enabled, rate_limit_enabled, rate_limit_per_minute, rate_limit_per_hour, notification_enabled}` |

All other timestamps are epoch milliseconds. Errors look like:

```json
{"success": false, "error": "Missing or invalid API key", "error_code": "AUTHENTICATION_FAILED", "timestamp": 1760000000000}
```

Codes: `AUTHENTICATION_FAILED` (401), `INVALID_REQUEST` (400/405/413), `INVALID_JSON` (400), `NOT_FOUND` (404),
`RATE_LIMIT_EXCEEDED` (429, with `Retry-After`), `SMS_SEND_FAILED` (500), `INTERNAL_ERROR` (500).

### Behaviour worth knowing

* **Phone numbers** must be strict E.164 (`+` then 8-15 digits, no spaces). `9876543210` is rejected.
* **`request_id` is an idempotency key.** If a non-failed message with the same `request_id` exists, `/api/send`
  returns that record (`200`, message "Duplicate request_id; returning existing record") and does **not** send again.
  A previous `FAILED` send may be retried with the same id. For a genuine OTP resend, use a new `request_id`.
* **Rate limiting** (default 10/minute and 100/hour, editable in Settings) counts SMS sends per API key; each
  message in a bulk request counts once. A bulk request that would exceed the limit is rejected as a whole with 429.
* **Bulk** returns HTTP 200 with per-item `results[]`; `success` is `true` only if every item was accepted.
  Invalid items are reported in `results` and not sent.
* **Status flow:** `PENDING` (handed to the app) → `SENT` (radio accepted every part) → `DELIVERED` (carrier delivery
  report) or `FAILED`. `UNKNOWN` is set for messages stuck in `PENDING` for over 10 minutes (e.g. the app was killed).
  Many Indian carriers/SIMs do not return delivery reports, so `SENT` may be the last status you see.
* Long texts are split into multipart SMS automatically (max 1600 characters per request).
* `simSubscriptionId` (or `sim_subscription_id`) selects a SIM on dual-SIM phones; omit it for the default SIM.
* If the phone rejects a send immediately, `/api/send` answers `500 SMS_SEND_FAILED` and includes `sms_id`.
  Failures reported later by the radio only show up via `/api/status` and `/api/history`.

### curl examples

```bash
BASE=http://192.168.1.50:8080/api
KEY=your-64-char-hex-key

curl $BASE/info

curl -X POST $BASE/send \
  -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"phone_number":"+919876543210","message":"Your verification code is 482915. It expires in 5 minutes.","request_id":"otp-1001"}'

curl -H "Authorization: Bearer $KEY" "$BASE/status?request_id=otp-1001"

curl -H "X-API-Key: $KEY" "$BASE/history?limit=20&status=FAILED"
# a literal + in a query string must be URL-encoded as %2B
curl -H "X-API-Key: $KEY" "$BASE/history?phone_number=%2B919876543210"

curl -X POST $BASE/send-bulk -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"messages":[{"phone_number":"+919876543210","message":"Code 111111"},{"phone_number":"+919812345678","message":"Code 222222"}]}'
```

### Node.js (backend) example

```js
// Node 18+ (global fetch). Keep GATEWAY_URL / GATEWAY_KEY in environment variables.
const crypto = require("node:crypto");

async function sendOtp(phone) {
  const otp = crypto.randomInt(100000, 1000000).toString(); // 6 digits, cryptographically random
  const res = await fetch(`${process.env.GATEWAY_URL}/api/send`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-API-Key": process.env.GATEWAY_KEY },
    body: JSON.stringify({
      phone_number: phone,                       // "+91XXXXXXXXXX"
      message: `${otp} is your verification code. Do not share it with anyone.`,
      request_id: crypto.randomUUID(),
    }),
    signal: AbortSignal.timeout(10_000),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(`${data.error_code}: ${data.error}`);
  return { otp, smsId: data.sms_id, requestId: data.request_id };
}
```

Store only a hash of the OTP on your side and expire it quickly.

## Smoke test

```bash
BASE_URL=http://192.168.1.50:8080 API_KEY=<key> ./scripts/smoke-test.sh                       # read-only checks
BASE_URL=http://192.168.1.50:8080 API_KEY=<key> PHONE=+919876543210 ./scripts/smoke-test.sh   # also sends a real SMS
```

## Remote access from the cloud

The phone sits behind mobile data / home NAT, so a server on the internet cannot connect to it directly.
Do **not** port-forward the raw HTTP port: traffic is plain HTTP and the only protection is the API key.
Use one of the options below so the public side is HTTPS.

### Option 1: Cloudflare Tunnel (recommended)

Run `cloudflared` somewhere that can reach the phone and publish an HTTPS hostname.

*On a PC / Raspberry Pi on the same Wi-Fi as the phone* (or on the phone itself under Termux with `pkg install cloudflared`):

```bash
cloudflared tunnel login
cloudflared tunnel create sms-gateway
cloudflared tunnel route dns sms-gateway sms.example.com
```

`~/.cloudflared/config.yml`:

```yaml
tunnel: sms-gateway
credentials-file: /home/you/.cloudflared/<TUNNEL-UUID>.json
ingress:
  - hostname: sms.example.com
    service: http://192.168.1.50:8080     # phone's LAN IP (use http://localhost:8080 when running in Termux)
  - service: http_status:404
```

```bash
cloudflared tunnel run sms-gateway        # or: cloudflared service install
```

Give the phone a fixed IP (DHCP reservation) so the `service:` URL keeps working.
Your backend then calls `https://sms.example.com/api/send` with the API key.
For a quick test without a domain: `cloudflared tunnel --url http://192.168.1.50:8080` prints a temporary `https://*.trycloudflare.com` URL.

Hardening: add a Cloudflare Access policy with a **service token** and send `CF-Access-Client-Id` / `CF-Access-Client-Secret` from your backend in addition to the gateway API key.

In the app, *Settings → External domain mode* → enter `https://sms.example.com` so the Server tab shows that URL (display only).

### Option 2: Nginx reverse proxy on your VPS

Nginx needs a private path to the phone: a VPN such as WireGuard or Tailscale, or an SSH reverse tunnel.

```bash
# Tailscale: install on the VPS and the phone, then use the phone's 100.x.y.z address below.

# SSH reverse tunnel from a device on the phone's network (autossh keeps it alive):
autossh -M 0 -N -R 127.0.0.1:18080:192.168.1.50:8080 user@vps.example.com
```

`/etc/nginx/sites-available/sms-gateway`:

```nginx
server {
    listen 443 ssl http2;
    server_name sms.example.com;

    ssl_certificate     /etc/letsencrypt/live/sms.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/sms.example.com/privkey.pem;

    # optional: only your backend's IP
    # allow 203.0.113.10; deny all;

    location /api/ {
        proxy_pass http://100.101.102.103:8080;   # Tailscale IP of the phone, or http://127.0.0.1:18080 for the SSH tunnel
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_connect_timeout 5s;
        proxy_read_timeout 30s;
        client_max_body_size 1m;
    }
    location / { return 404; }
}
```

```bash
sudo certbot --nginx -d sms.example.com
sudo nginx -t && sudo systemctl reload nginx
```

## Security notes

* The API key is 32 random bytes (hex) generated on first launch, stored in the app's private SharedPreferences, compared in constant time, and never logged.
  Regenerating it in the app invalidates the old key immediately.
* Message text, including OTPs, is stored in the local history database. Keep retention short (Settings), or tap *Delete all*.
  Notifications only show the last four digits of a number.
* On a LAN the API is plain HTTP. Use HTTPS (tunnel / proxy) for anything that leaves your network.
* Android caps how many SMS an app may send in a short period (roughly 30 per 30 minutes on many versions) and carriers may filter
  SMS from consumer SIMs. In India, application-to-person traffic is subject to TRAI DLT rules; a gateway on a personal SIM is fine for
  low-volume testing but check your carrier's terms before relying on it for production OTP traffic.

## Project layout

```
app/src/main/kotlin/com/smsgateway/app/
  GatewayApp.kt, Prefs.kt            application, settings, API key
  data/                              Room entity, DAO, database
  sms/                               SmsSender, SmsStatusReceiver (SENT / DELIVERED callbacks)
  server/                            GatewayServer (REST API), RateLimiter
  service/                           GatewayService (foreground), notifications, server state, IP lookup
  ui/                                MainActivity, MainViewModel, Server / History / Settings screens
scripts/smoke-test.sh
USER_GUIDE.md                       using the app: install, tab-by-tab walkthrough, troubleshooting
```
