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
`summary`); for `/feedback` the message, email and app version; plus any error. The console
(journald) gets only a concise one-liner (`[req] …`); the full detail (incl. AI output / email)
stays in the local DB.

Inspect it with:
```bash
sqlite3 usage.db "SELECT ts, endpoint, user_id, status, outcome, latency_ms FROM request_log ORDER BY id DESC LIMIT 20;"
sqlite3 usage.db "SELECT ts, user_id, substr(summary,1,80) FROM request_log WHERE endpoint='analyze' AND outcome='success' ORDER BY id DESC LIMIT 10;"
```

## Security note

The Gemini key is no longer in the APK (the goal). `PROXY_TOKEN` *is* still embedded in the app and
thus extractable; it's a lightweight abuse-gate (rotate it if leaked), not a strong secret. For
stronger protection later, have the server verify a Firebase **ID token** instead (the app already
has the Firebase auth scaffold).
