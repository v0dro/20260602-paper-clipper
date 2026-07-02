// Two-pass Gemini flow — a faithful TypeScript port of the analyze path in server/app.py
// (transcription pass + cleanup pass, retry policy, JSON-salvage parsing, usage accounting).
// Keep the two in sync: any behavioural change there should be mirrored here.

import { CLEANUP_PROMPT, PROMPT } from "./prompts";

const GEMINI_TIMEOUT_MS = 90_000;

function geminiEndpoint(model: string): string {
  return `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`;
}

/** Token usage accumulated across every Gemini call one /analyze request makes. */
export interface UsageAcc {
  promptTokens: number;
  outputTokens: number;
  totalTokens: number;
  thoughtsTokens: number;
  cachedTokens: number;
  imageTokens: number;
  geminiCalls: number;
  modelVersion: string | null;
}

/** Outcome of the full two-pass flow. `usage` is always present — tokens may have been spent
 * even when the result is rejected (no-text) or every attempt failed. */
export type AnalyzeOutcome =
  | { kind: "ok"; extractedText: string; summary: string; heading: string; usage: UsageAcc }
  | { kind: "no_text"; usage: UsageAcc }
  | { kind: "failed"; error: string; usage: UsageAcc };

/**
 * Runs the transcription pass (up to 3 attempts) and, on success, the cleanup pass.
 * Mirrors server/app.py's analyze(): retries network errors, 5xx/429 and empty/unparseable
 * responses; does NOT retry hard client errors (400/401/403) or the model's definitive
 * "no text in this image" verdict.
 */
export async function analyzeImage(
  apiKey: string,
  model: string,
  mimeType: string,
  imageBase64: string,
): Promise<AnalyzeOutcome> {
  const body = {
    contents: [
      {
        parts: [
          { inline_data: { mime_type: mimeType, data: imageBase64 } },
          { text: PROMPT },
        ],
      },
    ],
    generationConfig: {
      responseMimeType: "application/json",
      // gemini-2.5-flash is a "thinking" model; for transcription we don't need it, and leaving
      // it on occasionally consumes the token budget and returns empty/truncated JSON -> 502.
      thinkingConfig: { thinkingBudget: 0 },
      maxOutputTokens: 8192,
    },
  };

  let lastError = "Gemini request failed";
  const usage = newUsageAcc(); // token usage across every Gemini call this request makes
  for (let attempt = 1; attempt <= 3; attempt++) {
    let resp: Response;
    let raw: string;
    try {
      resp = await fetch(geminiEndpoint(model), {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": apiKey },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(GEMINI_TIMEOUT_MS),
      });
      // Reading the body must sit inside the try: fetch() resolves at response headers, so a
      // connection cut (or the timeout firing) mid-body rejects here — httpx reads the whole
      // body inside client.post(), so the server catches and retries this case too.
      raw = await resp.text();
    } catch (exc) {
      lastError = `Could not reach Gemini: ${exc}`;
      console.log(`[analyze] attempt ${attempt}: network error: ${exc}`);
      continue;
    }

    if (Math.floor(resp.status / 100) !== 2) {
      lastError = geminiError(raw, resp.status);
      console.log(`[analyze] attempt ${attempt}: HTTP ${resp.status}: ${raw.slice(0, 300)}`);
      // Don't retry hard client errors (bad key / forbidden / bad request).
      if (resp.status === 400 || resp.status === 401 || resp.status === 403) break;
      continue;
    }

    // The call succeeded, so Gemini spent tokens — record them even if we ultimately reject the
    // result (no-text / unparseable). This makes the reported usage reflect true cost per request.
    accumulateUsage(usage, raw);

    const { extractedText, summary, heading, hasText } = parseGemini(raw);

    // The model determined the image has no transcribable text (e.g. a photo of a car, not a
    // clipping). That's a definitive verdict, not a transient failure: don't retry. The caller
    // turns this into a 422 (and doesn't count it against the daily cap) — but the tokens it
    // cost are still reported.
    if (hasText === false) {
      console.log(`[analyze] attempt ${attempt}: no text detected in image`);
      return { kind: "no_text", usage };
    }

    if (extractedText === null || (!extractedText && !summary)) {
      lastError = "Gemini returned no usable text";
      console.log(`[analyze] attempt ${attempt}: empty/unparseable: ${raw.slice(0, 300)}`);
      continue;
    }

    // Second pass: rewrite the raw transcription into a clean, presentable article. The raw
    // text is thrown away — only the cleaned article is returned. Falls back to the raw text
    // if the cleanup call fails, so a clipping is never left with no body. Its tokens count too.
    let article = "";
    if (extractedText) {
      const [cleaned, cleanupRaw] = await cleanupArticle(apiKey, model, extractedText);
      accumulateUsage(usage, cleanupRaw);
      article = cleaned || extractedText;
    }

    return { kind: "ok", extractedText: article, summary, heading, usage };
  }

  return { kind: "failed", error: lastError, usage };
}

/**
 * Second Gemini pass: rewrite raw transcription into a clean article.
 *
 * Returns [cleanedArticle, rawResponseJson]. The raw response is returned even when the body
 * can't be parsed, so the caller can still bill the tokens it consumed; it's '' only when no
 * Gemini call completed (network error / HTTP error). No retry — the caller falls back to the
 * raw transcription.
 */
async function cleanupArticle(
  apiKey: string,
  model: string,
  rawText: string,
): Promise<[string, string]> {
  const body = {
    contents: [{ parts: [{ text: `${CLEANUP_PROMPT}\n\n---\n${rawText}` }] }],
    generationConfig: {
      thinkingConfig: { thinkingBudget: 0 },
      maxOutputTokens: 8192,
    },
  };
  let resp: Response;
  let raw: string;
  try {
    resp = await fetch(geminiEndpoint(model), {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": apiKey },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(GEMINI_TIMEOUT_MS),
    });
    // Inside the try for the same reason as the transcription pass: a mid-body failure must
    // degrade to the raw-text fallback, not escape and crash the request.
    raw = await resp.text();
  } catch (exc) {
    console.log(`[cleanup] network error: ${exc}`);
    return ["", ""];
  }
  if (Math.floor(resp.status / 100) !== 2) {
    console.log(`[cleanup] HTTP ${resp.status}: ${raw.slice(0, 200)}`);
    return ["", ""];
  }
  try {
    const text = (JSON.parse(raw) as any).candidates[0].content.parts[0].text;
    if (text === undefined) throw new TypeError("no text part");
    return [String(text).trim(), raw];
  } catch {
    console.log(`[cleanup] unparseable response: ${raw.slice(0, 200)}`);
    return ["", raw];
  }
}

interface ParsedGemini {
  extractedText: string | null;
  summary: string;
  heading: string;
  hasText: boolean | null;
}

/**
 * Port of server/app.py's _parse_gemini().
 *
 * extractedText is null if the shape is wrong (transient — caller may retry). hasText is the
 * model's verdict on whether the image contains transcribable text: true/false when stated, or
 * null when it couldn't be determined.
 *
 * The model emits hasText + heading + summary before the (potentially long) transcription, so if
 * the output is truncated at the token limit the JSON won't parse strictly. In that case we
 * salvage the complete leading fields with a regex fallback instead of throwing the whole result
 * away.
 */
export function parseGemini(raw: string): ParsedGemini {
  let text: string;
  try {
    const t = (JSON.parse(raw) as any).candidates[0].content.parts[0].text;
    if (t === undefined) throw new TypeError("no text part");
    text = String(t);
  } catch {
    return { extractedText: null, summary: "", heading: "", hasText: null };
  }

  try {
    const inner = JSON.parse(text) as any;
    if (inner !== null && typeof inner === "object" && !Array.isArray(inner)) {
      return {
        extractedText: String(inner.extractedText ?? "").trim(),
        summary: String(inner.summary ?? "").trim(),
        heading: String(inner.heading ?? "").trim(),
        hasText: coerceBool(inner.hasText),
      };
    }
  } catch {
    // fall through to the salvage below
  }

  // Truncated/malformed JSON: pull out whatever complete fields we can.
  const extracted = jsonStringField(text, "extractedText");
  const summary = jsonStringField(text, "summary");
  const heading = jsonStringField(text, "heading");
  const hasText = jsonBoolField(text, "hasText");
  if (!extracted && !summary && !heading && hasText === null) {
    return { extractedText: null, summary: "", heading: "", hasText: null };
  }
  return { extractedText: extracted, summary, heading, hasText };
}

/** Extracts one JSON string field's value from possibly-truncated text. '' if absent/incomplete.
 * Python original: re.search(rf'"{name}"\s*:\s*"((?:[^"\\]|\\.)*)"', text). */
function jsonStringField(text: string, name: string): string {
  const match = new RegExp(`"${name}"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"`).exec(text);
  if (!match) return "";
  try {
    return (JSON.parse(`"${match[1]}"`) as string).trim(); // unescape via JSON
  } catch {
    return "";
  }
}

/** Best-effort bool from the model's hasText field (accepts real bools or "true"/"false"). */
function coerceBool(value: unknown): boolean | null {
  if (typeof value === "boolean") return value;
  if (typeof value === "string") {
    const v = value.trim().toLowerCase();
    if (v === "true") return true;
    if (v === "false") return false;
  }
  return null;
}

/** Extracts a JSON boolean field from possibly-truncated text. null if absent.
 * Python original: re.search(rf'"{name}"\s*:\s*(true|false)', text, re.IGNORECASE). */
function jsonBoolField(text: string, name: string): boolean | null {
  const match = new RegExp(`"${name}"\\s*:\\s*(true|false)`, "i").exec(text);
  if (!match) return null;
  return match[1].toLowerCase() === "true";
}

/** int(x or 0) with Python's forgiveness: non-numeric/absent values count as 0. */
function toInt(value: unknown): number {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? Math.trunc(n) : 0;
}

/** Pulls token usage (and the resolved model version) from one Gemini response.
 * null if the response isn't JSON at all (no call to bill). */
function usageFromResponse(raw: string): {
  promptTokens: number;
  outputTokens: number;
  totalTokens: number;
  thoughtsTokens: number;
  cachedTokens: number;
  imageTokens: number;
  modelVersion: string | null;
} | null {
  let data: any;
  try {
    data = JSON.parse(raw);
  } catch {
    return null;
  }
  const meta = data?.usageMetadata ?? {};

  const modality = (details: unknown, name: string): number => {
    let sum = 0;
    for (const d of Array.isArray(details) ? details : []) {
      if (String(d?.modality ?? "").toUpperCase() === name) sum += toInt(d?.tokenCount);
    }
    return sum;
  };

  return {
    promptTokens: toInt(meta.promptTokenCount),
    outputTokens: toInt(meta.candidatesTokenCount),
    totalTokens: toInt(meta.totalTokenCount),
    thoughtsTokens: toInt(meta.thoughtsTokenCount),
    cachedTokens: toInt(meta.cachedContentTokenCount),
    imageTokens: modality(meta.promptTokensDetails, "IMAGE"),
    modelVersion: typeof data?.modelVersion === "string" ? data.modelVersion : null,
  };
}

function newUsageAcc(): UsageAcc {
  return {
    promptTokens: 0,
    outputTokens: 0,
    totalTokens: 0,
    thoughtsTokens: 0,
    cachedTokens: 0,
    imageTokens: 0,
    geminiCalls: 0,
    modelVersion: null,
  };
}

/** Adds one Gemini response's token usage into the per-request accumulator [acc]. */
function accumulateUsage(acc: UsageAcc, raw: string): void {
  const usage = usageFromResponse(raw);
  if (!usage) return;
  acc.promptTokens += usage.promptTokens;
  acc.outputTokens += usage.outputTokens;
  acc.totalTokens += usage.totalTokens;
  acc.thoughtsTokens += usage.thoughtsTokens;
  acc.cachedTokens += usage.cachedTokens;
  acc.imageTokens += usage.imageTokens;
  acc.geminiCalls += 1;
  if (usage.modelVersion) acc.modelVersion = usage.modelVersion;
}

/** Best human-readable message from a non-2xx Gemini response body. */
function geminiError(raw: string, status: number): string {
  try {
    const message = (JSON.parse(raw) as any).error.message;
    if (typeof message !== "string") throw new TypeError("no error message");
    return message;
  } catch {
    return `Gemini request failed (HTTP ${status})`;
  }
}
