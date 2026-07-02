# Paper Clipper Worker (fallback AI proxy)

A **Cloudflare Worker** that replicates the home server's `POST /analyze` (see `server/README.md`)
so the app still works when the home machine or its tunnel is down. Same Bearer auth, same request
body, same prompts, same two-pass Gemini flow, same error texts/status codes — plus a `usage`
object in the response, because the worker can't write the home server's `usage.db`: the app
caches that usage on-device and uploads it to the home server ≥24 h later via `POST /report-usage`.

```
phone (app) ──HTTPS──> clipper.<yourdomain> ──tunnel──> home server ──> Gemini   (primary)
                └──── on tunnel failure ────> this worker ─────────────> Gemini   (fallback)
```

`/feedback` deliberately does **not** fall back — feedback is non-urgent and lands as `.eml`
files on the home machine, so the app simply retries it later.

## API

- `GET /health` → `{"status":"ok","model":"gemini-2.5-flash","backend":"worker"}`
  (`backend` tells you which side answered a shared hostname).
- `POST /analyze` — header `Authorization: Bearer <PROXY_TOKEN>`, optional `X-User-Id`, body
  `{"mimeType":"image/jpeg","imageBase64":"..."}` →

  ```json
  { "extractedText": "...", "summary": "...", "heading": "...",
    "usage": { "promptTokens": 0, "outputTokens": 0, "totalTokens": 0,
               "thoughtsTokens": 0, "cachedTokens": 0, "imageTokens": 0,
               "geminiCalls": 2, "modelVersion": "...", "latencyMs": 8123 } }
  ```

  Errors mirror the server (`{"error":"..."}`, same texts): `401` bad/missing token, `400` bad
  body/base64, `422` no readable text, `429` daily limit, `502` Gemini failure. `422` and `502`
  **also include `usage`** — tokens were spent, and the app reports those too.

## Setup & local dev

```bash
cd worker
npm install
cp .dev.vars.example .dev.vars     # fill in GEMINI_API_KEY + PROXY_TOKEN (gitignored)
npx wrangler dev                   # local simulation on http://localhost:8787
```

Smoke it:

```bash
curl localhost:8787/health
curl -X POST localhost:8787/analyze \
  -H "Authorization: Bearer $PROXY_TOKEN" -H "Content-Type: application/json" \
  -d "{\"mimeType\":\"image/jpeg\",\"imageBase64\":\"$(base64 -w0 clipping.jpg)\"}"
```

## Deploy

Needs a (free) Cloudflare account: `npx wrangler login` first.

1. **KV namespace** for the per-user daily limit:
   ```bash
   npx wrangler kv namespace create USAGE_KV
   ```
   Paste the printed id into `wrangler.toml` (`kv_namespaces` → `id`; account-specific, not
   secret). If you skip this and remove the binding, the worker just runs without a daily limit.
2. **Secrets** (never in wrangler.toml / git):
   ```bash
   npx wrangler secret put GEMINI_API_KEY
   npx wrangler secret put PROXY_TOKEN
   ```
   `PROXY_TOKEN` **must equal the home server's** (`server/.env`) — the app embeds a single token
   and sends it to whichever backend it talks to.
3. **Deploy**:
   ```bash
   npx wrangler deploy
   ```
   Then put the printed `https://paper-clipper-worker.<account>.workers.dev` URL into the app's
   `local.properties` as `WORKER_URL` and rebuild.

### Custom domain (optional)

To serve from your own hostname instead of `*.workers.dev`, uncomment the `routes` block in
`wrangler.toml` and set a hostname on a zone in your Cloudflare account; `wrangler deploy` creates
the DNS record. Don't reuse the tunnel hostname — the app needs the two URLs to be distinct so the
fallback actually goes somewhere else.

## Daily limit (Workers KV)

Key `usage:<userId>:<YYYY-MM-DD>` (UTC), TTL 2 days, incremented **only** on a successful
analysis — a `422` doesn't count, mirroring the server. KV increments aren't atomic, so treat the
limit as a soft abuse-gate, not an exact ledger. The count is independent of the home server's
SQLite counter: after a failover a user could get up to 2× `DAILY_LIMIT` in one day, which is an
accepted trade-off (the device also keeps its own counter).

## Workers Free CPU caveat

The Free plan allows ~10 ms CPU per request. The worker never base64-decodes the image (byte size
is derived arithmetically from the base64 length), but `JSON.parse` on a multi-MB request body is
still the CPU hot spot. **Test with a real full-size camera image after deploying** — if you hit
`Exceeded CPU Limits`, the $5/mo Workers Paid plan raises the budget to 30 s. Wall-clock time
waiting on Gemini does not count as CPU time.
