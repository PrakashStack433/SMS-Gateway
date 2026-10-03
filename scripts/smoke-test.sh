#!/usr/bin/env bash
# Smoke test for the SMS Gateway app.
#
#   BASE_URL=http://192.168.1.50:8080 API_KEY=<key> ./scripts/smoke-test.sh
#   BASE_URL=https://sms.example.com  API_KEY=<key> PHONE=+919876543210 ./scripts/smoke-test.sh
#
# BASE_URL  server root, without /api (default http://localhost:8080)
# API_KEY   key shown in the app's Server tab (required)
# PHONE     E.164 number to send a real test SMS to. If unset, the send test is skipped.
# MESSAGE   text for the test SMS (default: "SMS Gateway smoke test")
set -u

BASE_URL="${BASE_URL:-http://localhost:8080}"
BASE_URL="${BASE_URL%/}"
BASE_URL="${BASE_URL%/api}"
API_KEY="${API_KEY:-}"
PHONE="${PHONE:-}"
MESSAGE="${MESSAGE:-SMS Gateway smoke test}"

if [ -z "$API_KEY" ]; then
  echo "API_KEY is required (copy it from the app's Server tab)." >&2
  exit 2
fi
command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 2; }

pass=0
fail=0
body_file="$(mktemp)"
trap 'rm -f "$body_file"' EXIT

# check <name> <expected-http-code> <expected-body-substring> <curl args...>
check() {
  local name="$1" want_code="$2" want_body="$3"
  shift 3
  local code
  code="$(curl -sS -m 20 -o "$body_file" -w '%{http_code}' "$@" 2>"$body_file.err")" || code="000"
  if [ "$code" = "$want_code" ] && grep -q -- "$want_body" "$body_file"; then
    echo "PASS  $name (HTTP $code)"
    pass=$((pass + 1))
  else
    echo "FAIL  $name (HTTP $code, expected $want_code, body must contain: $want_body)"
    [ -s "$body_file.err" ] && sed 's/^/      curl: /' "$body_file.err"
    sed 's/^/      body: /' "$body_file"
    echo
    fail=$((fail + 1))
  fi
  rm -f "$body_file.err"
}

echo "Target: $BASE_URL/api"
echo

check "GET  /api/info (no auth)" 200 '"app_name"' "$BASE_URL/api/info"
check "GET  /api/config rejects missing key" 401 'AUTHENTICATION_FAILED' "$BASE_URL/api/config"
check "GET  /api/config with X-API-Key" 200 '"server_port"' -H "X-API-Key: $API_KEY" "$BASE_URL/api/config"
check "GET  /api/config with Bearer token" 200 '"rate_limit_enabled"' -H "Authorization: Bearer $API_KEY" "$BASE_URL/api/config"
check "POST /api/send rejects bad phone" 400 'INVALID_REQUEST' \
  -X POST -H "X-API-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d '{"phone_number":"9876543210","message":"x"}' "$BASE_URL/api/send"
check "POST /api/send rejects invalid JSON" 400 'INVALID_JSON' \
  -X POST -H "X-API-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d '{not json' "$BASE_URL/api/send"

if [ -n "$PHONE" ]; then
  REQUEST_ID="smoke-$(date +%s)-$$"
  check "POST /api/send queues an SMS" 200 '"status":"PENDING"' \
    -X POST -H "X-API-Key: $API_KEY" -H 'Content-Type: application/json' \
    -d "{\"phone_number\":\"$PHONE\",\"message\":\"$MESSAGE\",\"request_id\":\"$REQUEST_ID\"}" \
    "$BASE_URL/api/send"
  sleep 3
  check "GET  /api/status?request_id=..." 200 '"status"' \
    -H "X-API-Key: $API_KEY" "$BASE_URL/api/status?request_id=$REQUEST_ID"
  echo "      status: $(tr -d '\n ' < "$body_file" | head -c 400)"
  check "GET  /api/history" 200 '"total_count"' \
    -H "X-API-Key: $API_KEY" "$BASE_URL/api/history?limit=5"
else
  echo "SKIP  POST /api/send (set PHONE=+91XXXXXXXXXX to send a real test SMS)"
fi

echo
echo "Passed: $pass   Failed: $fail"
[ "$fail" -eq 0 ]
