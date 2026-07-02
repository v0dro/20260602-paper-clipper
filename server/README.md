# Paper Clipper AI proxy

A small FastAPI service that the Paper Clipper Android app calls instead of talking to Gemini
directly. It holds the **Gemini API key server-side** (so the key never ships in the APK),
forwards the clipping image to Gemini, and returns the transcription + summary. It's exposed to
the internet from this always-on machine via a **Cloudflare named tunnel** (stable hostname).

```
phone (app)  ──HTTPS──>  clipper.<yourdomain>  ──Cloudflare tunnel──>  localhost:8000 (this server)  ──>  Gemini
```

## API

- `GET /health` → `{"status":"ok","model":"gemini-2.5-flash"}`
- `POST /analyze` — header `Authorization: Bearer <PROXY_TOKEN>`, body:
  ```json
  { "mimeType": "image/jpeg", "imageBase64": "<base64 image bytes>" }
  ```
  → `200 {"extractedText":"...","summary":"...","heading":"..."}` or an error as `{"error":"..."}`
  (`401` bad/missing token, `400` bad base64, `422` no readable text in the image,
  `429` daily limit, `502` Gemini failure).

  The model is asked to first judge whether the image actually contains transcribable text
  (`hasText`). If it doesn't — e.g. a photo of a car — the server returns `422` instead of an
  invented article, and the call is **not** retried or counted against the daily limit.

- `POST /report-usage` — header `Authorization: Bearer <PROXY_TOKEN>`, body:
  ```json
  { "reports": [ { "reportId": "uuid", "ts": "2026-07-02T04:12:33Z", "status": 200,
                   "userId": "...", "appVersion": "...", "mimeType": "image/jpeg",
                   "requestBytes": 123456, "latencyMs": 8123, "error": null,
                   "extractedText": "...", "summary": "...",
                   "promptTokens": 0, "outputTokens": 0, "totalTokens": 0,
                   "thoughtsTokens": 0, "cachedTokens": 0, "imageTokens": 0,
                   "geminiCalls": 2, "modelVersion": "..." } ] }
  ```
  → `200 {"accepted":["uuid",...],"duplicate":["uuid",...]}` (`401` bad/missing token, `400` more
  than **100** items in one batch). Per item only `reportId`, `ts` and `status` are required — the
  model is deliberately lenient so one odd field can't wedge the app's retry loop. See
  [Deferred usage reports](#deferred-usage-reports-cloudflare-worker-fallback).

## 1. Setup (pip + venv)

```bash
cd server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
```
Edit `.env`:
- `GEMINI_API_KEY=` your Gemini key (from Google AI Studio).
- `PROXY_TOKEN=` a long random secret — generate with
  `python3 -c "import secrets; print(secrets.token_urlsafe(32))"`.
  Use the **same** value in the app's `local.properties` (`PROXY_TOKEN=`).

Run locally to test:
```bash
.venv/bin/uvicorn app:app --host 127.0.0.1 --port 8000
curl localhost:8000/health
```

## 2. Cloudflare named tunnel

`cloudflared` is already installed at `~/.local/bin/cloudflared`. You need a free Cloudflare
account with a **domain added to Cloudflare** (DNS managed there).

```bash
cloudflared tunnel login                                   # browser auth; pick your domain
cloudflared tunnel create paperclipper                     # prints <TUNNEL_UUID>, writes ~/.cloudflared/<UUID>.json
cloudflared tunnel route dns paperclipper clipper.<yourdomain>
```
Then edit `cloudflared/config.yml`, replacing `<TUNNEL_UUID>` (twice) and `<yourdomain>`.
Test the tunnel:
```bash
cloudflared tunnel --config cloudflared/config.yml run paperclipper
# from another network: curl https://clipper.<yourdomain>/health
```

## 3. Always-on (systemd --user)

Runs on boot and restarts on crash, without you being logged in.

```bash
mkdir -p ~/.config/systemd/user
cp systemd/paperclipper-server.service ~/.config/systemd/user/
cp systemd/paperclipper-tunnel.service ~/.config/systemd/user/
loginctl enable-linger "$USER"          # lets user services run without an active login
systemctl --user daemon-reload
systemctl --user enable --now paperclipper-server paperclipper-tunnel
systemctl --user status paperclipper-server paperclipper-tunnel
journalctl --user -u paperclipper-server -f     # logs
```

To update after editing `app.py`: `systemctl --user restart paperclipper-server`.

## 4. Point the app at it

In the repo root `local.properties`:
```
SERVER_URL=https://clipper.<yourdomain>
PROXY_TOKEN=<same token as server/.env>
```
Rebuild/install the app. Remove `GEMINI_API_KEY` from `local.properties` — the key now lives only
in `server/.env`.

## Request log

Every request is recorded — one row per request — in a `request_log` table inside the same SQLite
file as the usage counters (`USAGE_DB`, default `usage.db`, gitignored). Each row holds the time,
endpoint, **user id** (`X-User-Id`), caller IP (from the Cloudflare forwarded headers), HTTP status,
outcome, latency, request size, and — for `/analyze` — the **AI response** (`extracted_text`,
`summary`) plus **token usage & estimated cost** (see below); for `/feedback` the message, email and
app version; plus any error. The console (journald) gets only a concise one-liner (`[req] …`, now
including `tokens=… cost=$…`); the full detail (incl. AI output / email) stays in the local DB.

### Token usage & cost per request

For each `/analyze` request the server reads Gemini's `usageMetadata` from **every** Gemini call it
makes (the transcription pass **and** the cleanup pass) and stores the summed counts:
`prompt_tokens`, `output_tokens`, `total_tokens`, `thoughts_tokens`, `cached_tokens`,
`image_tokens` (image-modality share of the prompt), `gemini_calls` (usually 2), `model_version`,
and `est_cost_usd`. Cost is computed from `GEMINI_PRICE_INPUT_PER_M` / `GEMINI_PRICE_OUTPUT_PER_M`
(default gemini-2.5-flash list prices). Tokens are recorded even when the result is rejected
(no-text `422`) or fails (`502`), so the log reflects true spend.

Inspect it with:
```bash
# recent requests
sqlite3 usage.db "SELECT ts, endpoint, user_id, status, outcome, latency_ms FROM request_log ORDER BY id DESC LIMIT 20;"
# per-request tokens + cost
sqlite3 usage.db "SELECT ts, user_id, gemini_calls, prompt_tokens, output_tokens, total_tokens, est_cost_usd FROM request_log WHERE endpoint='analyze' ORDER BY id DESC LIMIT 20;"
# AVERAGE tokens & cost per analyze request, plus totals
sqlite3 usage.db "SELECT COUNT(*) reqs, ROUND(AVG(total_tokens),1) avg_tokens, ROUND(AVG(est_cost_usd),6) avg_cost, ROUND(SUM(est_cost_usd),4) total_cost FROM request_log WHERE endpoint='analyze' AND total_tokens IS NOT NULL;"
# per-user, per-day token + cost totals
sqlite3 usage.db "SELECT substr(ts,1,10) day, user_id, SUM(total_tokens) tokens, ROUND(SUM(est_cost_usd),4) cost FROM request_log WHERE endpoint='analyze' AND total_tokens IS NOT NULL GROUP BY day, user_id ORDER BY day DESC, cost DESC;"
```

Rows logged before this feature have `NULL` token columns (hence the `IS NOT NULL` filter).

## Deferred usage reports (Cloudflare Worker fallback)

When this machine (or the tunnel) is down, the app falls back to a **Cloudflare Worker**
(`worker/`) that replicates `/analyze` — but the Worker can't write this machine's `usage.db`.
Instead the app caches a full usage report on-device for every fallback-served call and uploads
it here ≥24 h later via `POST /report-usage`, retrying until acknowledged.

Each report item becomes one ordinary `request_log` row (`endpoint='analyze'`), with:

- `ts` = the **client's original analyze-time timestamp**, stored verbatim (that's the point);
- `est_cost_usd` recomputed server-side from the reported token counts and this server's
  `GEMINI_PRICE_*` prices — the client never sends cost;
- `client_ip` left `NULL` (unknowable a day later).

Three `request_log` columns exist for these rows (added via the usual ALTER-TABLE mechanism,
`NULL` on all middleware-logged rows):

| Column | Meaning |
|---|---|
| `report_id` | Client-generated UUID; a **unique index** on it makes ingestion idempotent. |
| `via` | `'worker'` — the request was served by the Cloudflare Worker, not this server. |
| `received_at` | Server time (ISO-8601 UTC) when the report actually arrived here. |

**Dedup semantics:** ingestion is `INSERT OR IGNORE` on `report_id`, and the response splits ids
into `accepted` (stored now) and `duplicate` (already stored — e.g. a retry after a lost ack).
The app deletes its cached report on **either** list, so retries always converge. Batches are
capped at 100 items (`400` above that). Reports deliberately do **not** back-fill the daily-limit
`usage` table — they're ≥24 h stale by design.

```bash
# fallback-served requests, client time vs. arrival time
sqlite3 usage.db "SELECT ts, received_at, user_id, status, total_tokens, est_cost_usd FROM request_log WHERE via='worker' ORDER BY id DESC LIMIT 20;"
```

## Security note

The Gemini key is no longer in the APK (the goal). `PROXY_TOKEN` *is* still embedded in the app and
thus extractable; it's a lightweight abuse-gate (rotate it if leaked), not a strong secret. For
stronger protection later, have the server verify a Firebase **ID token** instead (the app already
has the Firebase auth scaffold).
