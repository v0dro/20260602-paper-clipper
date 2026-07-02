// Paper Clipper AI proxy — Cloudflare Worker fallback.
//
// Mirrors the home server's POST /analyze (server/app.py) so the app can fall back here when the
// Cloudflare tunnel to the home machine is down: same Bearer auth, same request body, same error
// texts and status codes — plus a `usage` object (token counts, latency) that the app caches
// on-device and later reports back to the home server, since this worker can't write its usage.db.
//
// /feedback is deliberately NOT replicated: feedback is non-urgent and lands as .eml files on the
// home machine, so the app just retries it later.

import { analyzeImage, UsageAcc } from "./gemini";

export interface Env {
  /** [vars] in wrangler.toml. DAILY_LIMIT mirrors the server's env default (100/day). */
  GEMINI_MODEL: string;
  DAILY_LIMIT: string;
  /** Secret (`wrangler secret put GEMINI_API_KEY`) — same key as server/.env. */
  GEMINI_API_KEY?: string;
  /** Secret (`wrangler secret put PROXY_TOKEN`) — MUST equal the home server's PROXY_TOKEN. */
  PROXY_TOKEN?: string;
  /** KV namespace for per-user daily counters. Optional: unbound = daily limit disabled. */
  USAGE_KV?: KVNamespace;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/health") {
        return json(200, { status: "ok", model: env.GEMINI_MODEL, backend: "worker" });
      }
      if (request.method === "POST" && url.pathname === "/analyze") {
        return await analyze(request, env);
      }
      return json(404, { error: "not found" });
    } catch (exc) {
      // Nothing may escape: an uncaught throw becomes Cloudflare's HTML 1101 page, but the app
      // expects every response — even a crash — to carry the JSON {"error": ...} shape.
      console.log(`[req] unhandled error: ${exc}`);
      return json(500, { error: "Internal server error" });
    }
  },
};

async function analyze(request: Request, env: Env): Promise<Response> {
  const started = Date.now();

  // --- auth (server parity: same 401/500 texts) ---
  if (!env.PROXY_TOKEN) {
    return json(500, { error: "Server PROXY_TOKEN is not configured" });
  }
  if (request.headers.get("authorization") !== `Bearer ${env.PROXY_TOKEN}`) {
    return json(401, { error: "Invalid or missing token" });
  }

  // --- request body ---
  let body: any;
  try {
    body = await request.json();
  } catch {
    return json(400, { error: "Invalid request body" });
  }
  const mimeType: unknown = body?.mimeType;
  const imageBase64: unknown = body?.imageBase64;
  if (typeof mimeType !== "string" || typeof imageBase64 !== "string") {
    return json(400, { error: "Invalid request body" });
  }

  // --- per-user daily cap (check before spending a Gemini call) ---
  const userId = (request.headers.get("x-user-id") ?? "anonymous").trim() || "anonymous";
  // NaN-only fallback: `|| 100` would coerce an explicit DAILY_LIMIT="0" (fallback switched
  // off) to 100, whereas the server's int(os.getenv(...)) honors 0 and 429s every request.
  const parsedLimit = parseInt(env.DAILY_LIMIT ?? "100", 10);
  const dailyLimit = Number.isNaN(parsedLimit) ? 100 : parsedLimit;
  const day = new Date().toISOString().slice(0, 10); // UTC YYYY-MM-DD
  const usageKey = `usage:${userId}:${day}`;
  let count = 0;
  if (env.USAGE_KV) {
    count = parseInt((await env.USAGE_KV.get(usageKey)) ?? "0", 10) || 0;
    if (count >= dailyLimit) {
      return json(429, {
        error: `Daily limit reached (${dailyLimit} analyses/day). Try again tomorrow.`,
      });
    }
  }

  // --- validate the image payload (client error before server-config error) ---
  // NEVER base64-decode here: a camera image is multiple MB and decoding it would burn the
  // Workers-Free 10 ms CPU budget for nothing — Gemini takes the base64 string as-is. A cheap
  // charset/length check stands in for the server's strict b64decode. Deliberately a touch
  // stricter: Python tolerates a dangling "=" on a non-multiple-of-4 string (e.g. "AAAA="),
  // which the app never emits; requiring length % 4 == 0 keeps the byte-count arithmetic exact.
  if (imageBase64.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(imageBase64)) {
    return json(400, { error: "imageBase64 is not valid base64" });
  }
  const padding = imageBase64.endsWith("==") ? 2 : imageBase64.endsWith("=") ? 1 : 0;
  const requestBytes = (imageBase64.length / 4) * 3 - padding;

  if (!env.GEMINI_API_KEY) {
    return json(500, { error: "Server GEMINI_API_KEY is not configured" });
  }

  // --- the two-pass Gemini flow (src/gemini.ts) ---
  const outcome = await analyzeImage(env.GEMINI_API_KEY, env.GEMINI_MODEL, mimeType, imageBase64);
  const usage = usageJson(outcome.usage, Date.now() - started);
  console.log(
    `[req] POST /analyze user=${userId} outcome=${outcome.kind} bytes=${requestBytes} ` +
      `tokens=${usage.totalTokens} calls=${usage.geminiCalls} ${usage.latencyMs}ms`,
  );

  // 422/502 still carry `usage`: tokens were spent, and the app queues these for the home
  // server's audit log just like successes.
  if (outcome.kind === "no_text") {
    return json(422, {
      error:
        "No readable text was detected in the image. " +
        "Point the camera at a newspaper clipping or printed text.",
      usage,
    });
  }
  if (outcome.kind === "failed") {
    return json(502, { error: outcome.error, usage });
  }

  // Count only successful analyses against the daily cap (mirrors the server: 422 doesn't
  // count). Read-modify-write isn't atomic in KV — the limit is a soft abuse-gate, not a ledger.
  if (env.USAGE_KV) {
    await env.USAGE_KV.put(usageKey, String(count + 1), { expirationTtl: 172800 }); // 2 days
  }

  return json(200, {
    extractedText: outcome.extractedText,
    summary: outcome.summary,
    heading: outcome.heading,
    usage,
  });
}

/** The `usage` object attached to 200/422/502 responses (the app caches it for later reporting). */
function usageJson(acc: UsageAcc, latencyMs: number) {
  return {
    promptTokens: acc.promptTokens,
    outputTokens: acc.outputTokens,
    totalTokens: acc.totalTokens,
    thoughtsTokens: acc.thoughtsTokens,
    cachedTokens: acc.cachedTokens,
    imageTokens: acc.imageTokens,
    geminiCalls: acc.geminiCalls,
    modelVersion: acc.modelVersion,
    latencyMs,
  };
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
