"""Paper Clipper AI proxy.

A tiny FastAPI service that the Android app calls instead of talking to Gemini directly.
It holds the Gemini API key server-side (so the key never ships in the APK), forwards the
clipping image to Gemini, and returns the transcription + summary.

Exposed to the internet via a Cloudflare named tunnel (see README.md).
"""

from __future__ import annotations

import base64
import json
import os
import re
import sqlite3
import threading
import time
from datetime import date, datetime, timezone
from email.message import EmailMessage
from email.utils import formatdate
from pathlib import Path

import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

load_dotenv()

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "").strip()
PROXY_TOKEN = os.getenv("PROXY_TOKEN", "").strip()
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash").strip()

# Token pricing (USD per 1M tokens) used to estimate per-request cost from Gemini's usageMetadata.
# Defaults are gemini-2.5-flash list prices ($0.30 input / $2.50 output per 1M). Override via env
# when changing GEMINI_MODEL. candidatesTokenCount already includes thinking tokens on the Gemini
# Developer API, so output cost covers thoughts without double-counting.
GEMINI_PRICE_INPUT_PER_M = float(os.getenv("GEMINI_PRICE_INPUT_PER_M", "0.30"))
GEMINI_PRICE_OUTPUT_PER_M = float(os.getenv("GEMINI_PRICE_OUTPUT_PER_M", "2.50"))

# Feedback is saved as .eml files in a local folder on this machine (no external email sent).
FEEDBACK_DIR = Path(os.getenv("FEEDBACK_DIR", "feedback")).expanduser()
FEEDBACK_TO = os.getenv("FEEDBACK_TO", "developer").strip()

# Per-user daily analysis cap (counts successful analyses; persisted in SQLite).
DAILY_LIMIT = int(os.getenv("DAILY_LIMIT", "100"))
USAGE_DB = Path(os.getenv("USAGE_DB", "usage.db")).expanduser()
_usage_lock = threading.Lock()


def _usage_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(USAGE_DB)
    conn.execute(
        "CREATE TABLE IF NOT EXISTS usage "
        "(user_id TEXT NOT NULL, day TEXT NOT NULL, count INTEGER NOT NULL, "
        "PRIMARY KEY (user_id, day))"
    )
    return conn


def usage_count(user_id: str, day: str) -> int:
    with _usage_lock, _usage_conn() as conn:
        row = conn.execute(
            "SELECT count FROM usage WHERE user_id = ? AND day = ?", (user_id, day)
        ).fetchone()
        return row[0] if row else 0


def usage_increment(user_id: str, day: str) -> None:
    with _usage_lock, _usage_conn() as conn:
        conn.execute(
            "INSERT INTO usage (user_id, day, count) VALUES (?, ?, 1) "
            "ON CONFLICT(user_id, day) DO UPDATE SET count = count + 1",
            (user_id, day),
        )
        conn.commit()


# --- per-request audit log -------------------------------------------------------------------
# Every request that reaches the server is recorded (one row) in the same SQLite file as the usage
# counters. Common fields (time, user, ip, status, latency) come from the logging middleware; the
# route handlers attach the richer details (the AI transcription/summary, the feedback text) via
# request.state.log_extra. Persisting locally keeps PII/AI output off stdout (journald) — the
# console only gets a concise one-liner.
_log_lock = threading.Lock()

# Columns persisted per request, in INSERT order. The token/cost columns are populated for
# /analyze from Gemini's usageMetadata (summed across the transcription + cleanup passes).
_LOG_COLUMNS = (
    "ts", "endpoint", "method", "path", "user_id", "client_ip", "status", "outcome",
    "latency_ms", "request_bytes", "mime_type", "app_version", "extracted_text", "summary",
    "feedback_message", "email", "error",
    "prompt_tokens", "output_tokens", "total_tokens", "thoughts_tokens", "cached_tokens",
    "image_tokens", "gemini_calls", "model_version", "est_cost_usd",
    "report_id", "via", "received_at",
)

# Columns added after the table first shipped — applied to existing DBs via ALTER TABLE so an old
# usage.db gains them without a manual migration.
_LOG_ADDED_COLUMNS = {
    "prompt_tokens": "INTEGER", "output_tokens": "INTEGER", "total_tokens": "INTEGER",
    "thoughts_tokens": "INTEGER", "cached_tokens": "INTEGER", "image_tokens": "INTEGER",
    "gemini_calls": "INTEGER", "model_version": "TEXT", "est_cost_usd": "REAL",
    # Deferred usage reports (/report-usage): the client-generated dedup id, how the request was
    # actually served ("worker"), and when the report reached this server.
    "report_id": "TEXT", "via": "TEXT", "received_at": "TEXT",
}


def _log_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(USAGE_DB)
    conn.execute(
        "CREATE TABLE IF NOT EXISTS request_log ("
        "id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, endpoint TEXT NOT NULL, "
        "method TEXT, path TEXT, user_id TEXT, client_ip TEXT, status INTEGER NOT NULL, "
        "outcome TEXT NOT NULL, latency_ms INTEGER, request_bytes INTEGER, mime_type TEXT, "
        "app_version TEXT, extracted_text TEXT, summary TEXT, feedback_message TEXT, "
        "email TEXT, error TEXT, prompt_tokens INTEGER, output_tokens INTEGER, "
        "total_tokens INTEGER, thoughts_tokens INTEGER, cached_tokens INTEGER, "
        "image_tokens INTEGER, gemini_calls INTEGER, model_version TEXT, est_cost_usd REAL)"
    )
    existing = {row[1] for row in conn.execute("PRAGMA table_info(request_log)")}
    for col, col_type in _LOG_ADDED_COLUMNS.items():
        if col not in existing:
            conn.execute(f"ALTER TABLE request_log ADD COLUMN {col} {col_type}")
    # Dedup key for /report-usage ingestion. SQLite allows unlimited NULLs under a unique index,
    # so the middleware's own rows (which never set report_id) are unaffected.
    conn.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_request_log_report_id ON request_log(report_id)"
    )
    conn.commit()
    return conn


def log_request(**fields) -> None:
    """Persists one request row. Logging must never break a request, so failures are swallowed."""
    try:
        with _log_lock, _log_conn() as conn:
            placeholders = ", ".join(["?"] * len(_LOG_COLUMNS))
            conn.execute(
                f"INSERT INTO request_log ({', '.join(_LOG_COLUMNS)}) VALUES ({placeholders})",
                tuple(fields.get(col) for col in _LOG_COLUMNS),
            )
            conn.commit()
    except Exception as exc:  # noqa: BLE001 - audit logging is best-effort
        print(f"[req-log] failed to persist: {exc}", flush=True)


def insert_usage_report(row: dict) -> bool:
    """Inserts one deferred usage report into request_log; False if its report_id is already there.

    INSERT OR IGNORE against the unique report_id index makes retried batches idempotent — a
    client that lost the ack simply re-sends and gets the ids back as duplicates. Unlike
    log_request, failures propagate: the client must not delete a report the server didn't store.
    """
    with _log_lock, _log_conn() as conn:
        placeholders = ", ".join(["?"] * len(_LOG_COLUMNS))
        cursor = conn.execute(
            f"INSERT OR IGNORE INTO request_log ({', '.join(_LOG_COLUMNS)}) "
            f"VALUES ({placeholders})",
            tuple(row.get(col) for col in _LOG_COLUMNS),
        )
        conn.commit()
        return cursor.rowcount == 1


GEMINI_ENDPOINT = (
    f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent"
)

# The newspaper-clipping prompt lives here now (moved off the device), so it can be tuned
# without rebuilding the app.
# SOURCE OF TRUTH SYNC: worker/src/prompts.ts carries a verbatim copy of PROMPT and
# CLEANUP_PROMPT for the Cloudflare Worker fallback — keep both files in sync when editing.
PROMPT = (
    "You are given a photograph. First determine whether it actually contains readable text to "
    "transcribe (a newspaper clipping, a printed page, a sign, handwriting, etc.). "
    "Respond with ONLY a JSON object with these four fields, in this exact order: "
    '"hasText" (boolean: true ONLY if the image contains real, legible text; false for photos '
    "with no meaningful text, such as a car, a person, an object, food or scenery), "
    '"heading" (a title of fewer than 5 words capturing the main topic), '
    '"summary" (a concise 2-3 sentence summary), and '
    '"extractedText" (a full, exact transcription of all readable text, no commentary). '
    "If hasText is false, set heading, summary and extractedText to empty strings. "
    "Transcribe only text that is genuinely present — never invent, guess or describe text that "
    "is not clearly legible in the image. Emit hasText, heading and summary first so they are "
    "never lost if the transcription is long. Do not include any other text."
)

# Second-pass prompt: the raw transcription above is messy (OCR errors, broken lines, stray
# captions/bylines/page furniture). This rewrites it into a clean, readable article. The raw
# first-pass transcription is discarded; only this cleaned article is shown in the app.
CLEANUP_PROMPT = (
    "Below is raw text transcribed from a newspaper clipping. It may contain OCR errors, "
    "words split across line breaks, hyphenation, and stray fragments such as captions, "
    "bylines, datelines or page furniture. Rewrite it into a clean, presentable article: fix "
    "obvious OCR mistakes, rejoin broken words and lines, drop the stray fragments, and "
    "organize it into well-formed paragraphs. Preserve the actual content and meaning and do "
    "NOT invent any facts. Respond with ONLY the cleaned article text, no preamble or commentary."
)

app = FastAPI(title="Paper Clipper AI proxy")

_OUTCOME_BY_STATUS = {
    401: "unauthorized",
    403: "unauthorized",
    429: "rate_limited",
    400: "bad_request",
    422: "bad_request",
}


@app.middleware("http")
async def log_requests(request: Request, call_next):
    """Records one audit row per request, merging handler-supplied details (request.state.log_extra)."""
    start = time.monotonic()
    request.state.log_extra = {}
    status = 500
    try:
        response = await call_next(request)
        status = response.status_code
        return response
    finally:
        latency_ms = int((time.monotonic() - start) * 1000)
        extra = getattr(request.state, "log_extra", {}) or {}
        outcome = _OUTCOME_BY_STATUS.get(
            status, "success" if status // 100 == 2 else "error"
        )
        # Behind the Cloudflare tunnel request.client is localhost; the real caller is in the
        # forwarded headers.
        client_ip = (
            request.headers.get("cf-connecting-ip")
            or (request.headers.get("x-forwarded-for") or "").split(",")[0].strip()
            or (request.client.host if request.client else None)
        )
        content_length = request.headers.get("content-length")
        row = {
            "ts": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "endpoint": request.url.path.lstrip("/") or "/",
            "method": request.method,
            "path": request.url.path,
            "user_id": request.headers.get("x-user-id"),
            "client_ip": client_ip,
            "status": status,
            "outcome": outcome,
            "latency_ms": latency_ms,
            "request_bytes": int(content_length) if content_length and content_length.isdigit() else None,
        }
        row.update(extra)  # handler details win (precise user_id, AI output, feedback, error)
        log_request(**row)
        tokens = row.get("total_tokens")
        cost = row.get("est_cost_usd")
        usage_str = (
            f" tokens={tokens} (in={row.get('prompt_tokens')} out={row.get('output_tokens')}"
            f" img={row.get('image_tokens')}) calls={row.get('gemini_calls')} cost=${cost}"
            if tokens is not None
            else ""
        )
        print(
            f"[req] {row['ts']} {row['method']} {row['path']} "
            f"user={row.get('user_id') or '-'} ip={client_ip or '-'} "
            f"status={status} outcome={outcome} {latency_ms}ms{usage_str}",
            flush=True,
        )


class AnalyzeRequest(BaseModel):
    mimeType: str
    imageBase64: str


class AnalyzeResponse(BaseModel):
    extractedText: str
    summary: str
    heading: str = ""


class FeedbackRequest(BaseModel):
    message: str
    email: str | None = None
    appVersion: str | None = None


class UsageReportItem(BaseModel):
    """One /analyze call the app served via the Cloudflare Worker fallback, reported after the fact.

    Deliberately lenient: only the identity/time/status trio is required, everything else has a
    sensible default and tolerates an explicit null — one odd field must never 422-wedge the
    app's retry loop forever.
    """

    reportId: str
    ts: str
    status: int
    userId: str | None = None
    appVersion: str | None = None
    mimeType: str | None = None
    requestBytes: int | None = None
    latencyMs: int | None = None
    error: str | None = None
    extractedText: str | None = None
    summary: str | None = None
    # int | None (not plain int) so a client sending null doesn't 422 the whole batch;
    # the endpoint coerces None back to 0 when building the row.
    promptTokens: int | None = 0
    outputTokens: int | None = 0
    totalTokens: int | None = 0
    thoughtsTokens: int | None = 0
    cachedTokens: int | None = 0
    imageTokens: int | None = 0
    geminiCalls: int | None = 0
    modelVersion: str | None = None


class UsageReportRequest(BaseModel):
    reports: list[UsageReportItem]


@app.post("/feedback")
async def feedback(
    req: FeedbackRequest,
    request: Request,
    authorization: str | None = Header(default=None),
):
    if not PROXY_TOKEN or authorization != f"Bearer {PROXY_TOKEN}":
        raise HTTPException(status_code=401, detail="Invalid or missing token")
    message = req.message.strip()
    if not message:
        raise HTTPException(status_code=400, detail="message is empty")

    request.state.log_extra = {
        "feedback_message": message,
        "email": req.email,
        "app_version": req.appVersion,
    }

    # Compose an RFC-822 mail and save it to the local feedback folder (no external send).
    mail = EmailMessage()
    mail["To"] = FEEDBACK_TO
    mail["From"] = req.email or "paper-clipper-app"
    mail["Subject"] = "Paper Clipper feedback"
    mail["Date"] = formatdate(localtime=True)
    if req.appVersion:
        mail["X-App-Version"] = req.appVersion
    mail.set_content(message)

    FEEDBACK_DIR.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    safe = re.sub(r"[^a-zA-Z0-9@._-]", "_", (req.email or "anon"))[:40]
    path = FEEDBACK_DIR / f"feedback_{stamp}_{safe}.eml"
    path.write_bytes(bytes(mail))
    print(f"[feedback] saved {path}", flush=True)
    return {"status": "saved"}


@app.get("/health")
async def health() -> dict:
    return {"status": "ok", "model": GEMINI_MODEL}


@app.post("/analyze", response_model=AnalyzeResponse)
async def analyze(
    req: AnalyzeRequest,
    request: Request,
    authorization: str | None = Header(default=None),
    x_user_id: str | None = Header(default=None),
):
    # --- auth ---
    if not PROXY_TOKEN:
        raise HTTPException(status_code=500, detail="Server PROXY_TOKEN is not configured")
    expected = f"Bearer {PROXY_TOKEN}"
    if authorization != expected:
        raise HTTPException(status_code=401, detail="Invalid or missing token")

    # --- per-user daily cap (check before spending a Gemini call) ---
    user_id = (x_user_id or "anonymous").strip() or "anonymous"
    today = date.today().isoformat()
    request.state.log_extra = {"user_id": user_id, "mime_type": req.mimeType}
    if usage_count(user_id, today) >= DAILY_LIMIT:
        raise HTTPException(
            status_code=429,
            detail=f"Daily limit reached ({DAILY_LIMIT} analyses/day). Try again tomorrow.",
        )

    # --- validate the image payload (client error before server-config error) ---
    try:
        image_bytes = base64.b64decode(req.imageBase64, validate=True)
    except Exception:
        raise HTTPException(status_code=400, detail="imageBase64 is not valid base64")
    request.state.log_extra["request_bytes"] = len(image_bytes)

    if not GEMINI_API_KEY:
        raise HTTPException(status_code=500, detail="Server GEMINI_API_KEY is not configured")

    body = {
        "contents": [
            {
                "parts": [
                    {"inline_data": {"mime_type": req.mimeType, "data": req.imageBase64}},
                    {"text": PROMPT},
                ]
            }
        ],
        "generationConfig": {
            "responseMimeType": "application/json",
            # gemini-2.5-flash is a "thinking" model; for transcription we don't need it, and leaving
            # it on occasionally consumes the token budget and returns empty/truncated JSON -> 502.
            "thinkingConfig": {"thinkingBudget": 0},
            "maxOutputTokens": 8192,
        },
    }

    # --- call Gemini, retrying transient failures (5xx / 429 / empty responses) ---
    last_error = "Gemini request failed"
    usage = _new_usage_acc()  # token usage across every Gemini call this request makes
    for attempt in range(1, 4):
        try:
            async with httpx.AsyncClient(timeout=90.0) as client:
                resp = await client.post(
                    GEMINI_ENDPOINT,
                    headers={
                        "Content-Type": "application/json",
                        "x-goog-api-key": GEMINI_API_KEY,
                    },
                    json=body,
                )
        except httpx.HTTPError as exc:
            last_error = f"Could not reach Gemini: {exc}"
            print(f"[analyze] attempt {attempt}: network error: {exc}", flush=True)
            continue

        if resp.status_code // 100 != 2:
            last_error = _gemini_error(resp.text, resp.status_code)
            print(f"[analyze] attempt {attempt}: HTTP {resp.status_code}: {resp.text[:300]}", flush=True)
            # Don't retry hard client errors (bad key / forbidden / bad request).
            if resp.status_code in (400, 401, 403):
                break
            continue

        # The call succeeded, so Gemini spent tokens — record them even if we ultimately reject the
        # result (no-text / unparseable). This makes the audit log reflect true cost per request.
        _accumulate_usage(usage, resp.text)

        extracted, summary, heading, has_text = _parse_gemini(resp.text)

        # The model determined the image has no transcribable text (e.g. a photo of a car, not a
        # clipping). That's a definitive verdict, not a transient failure: don't retry and don't
        # count it against the daily cap — but still log the tokens it cost. Return a clear 422 so
        # the app tells the user instead of saving an invented article.
        if has_text is False:
            _record_usage(request, usage)
            request.state.log_extra["error"] = "no_text_detected"
            print(f"[analyze] attempt {attempt}: no text detected in image", flush=True)
            raise HTTPException(
                status_code=422,
                detail=(
                    "No readable text was detected in the image. "
                    "Point the camera at a newspaper clipping or printed text."
                ),
            )

        if extracted is None or (not extracted and not summary):
            last_error = "Gemini returned no usable text"
            print(f"[analyze] attempt {attempt}: empty/unparseable: {resp.text[:300]}", flush=True)
            continue

        # Second pass: rewrite the raw transcription into a clean, presentable article. The raw
        # text is thrown away — only the cleaned article is returned. Falls back to the raw text
        # if the cleanup call fails, so a clipping is never left with no body. Its tokens count too.
        if extracted:
            cleaned, cleanup_raw = await _cleanup_article(extracted)
            _accumulate_usage(usage, cleanup_raw)
            article = cleaned or extracted
        else:
            article = ""

        usage_increment(user_id, today)  # count only successful analyses
        _record_usage(request, usage)
        request.state.log_extra.update(extracted_text=article, summary=summary)
        return AnalyzeResponse(extractedText=article, summary=summary, heading=heading)

    _record_usage(request, usage)  # log tokens spent even when all attempts failed
    request.state.log_extra["error"] = last_error
    raise HTTPException(status_code=502, detail=last_error)


@app.post("/report-usage")
async def report_usage(
    req: UsageReportRequest,
    request: Request,
    authorization: str | None = Header(default=None),
    x_user_id: str | None = Header(default=None),
):
    """Ingests deferred usage reports for /analyze calls the Cloudflare Worker fallback served.

    Each item becomes one request_log row, keeping the client's original analyze-time timestamp
    verbatim (that's the point of the reports). Idempotent via the unique report_id index, so the
    app retries batches until acknowledged without ever double-counting. Deliberately does NOT
    touch the daily-limit usage table — the reports arrive ≥24 h late by design.
    """
    # --- auth ---
    if not PROXY_TOKEN:
        raise HTTPException(status_code=500, detail="Server PROXY_TOKEN is not configured")
    expected = f"Bearer {PROXY_TOKEN}"
    if authorization != expected:
        raise HTTPException(status_code=401, detail="Invalid or missing token")

    user_id = (x_user_id or "anonymous").strip() or "anonymous"
    # The middleware's own audit row for this /report-usage call gets only the precise user_id;
    # the report contents belong in their own rows, never in this one.
    request.state.log_extra = {"user_id": user_id}

    if len(req.reports) > 100:
        raise HTTPException(status_code=400, detail="Too many reports in one batch (max 100)")

    received_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
    accepted: list[str] = []
    duplicate: list[str] = []
    for item in req.reports:
        row = {
            "ts": item.ts,  # client's device clock at analyze time, stored verbatim
            "endpoint": "analyze",
            "method": "POST",
            "path": "/analyze",
            "user_id": item.userId,
            "status": item.status,
            "outcome": _OUTCOME_BY_STATUS.get(
                item.status, "success" if item.status // 100 == 2 else "error"
            ),
            "latency_ms": item.latencyMs,
            "request_bytes": item.requestBytes,
            "mime_type": item.mimeType,
            "app_version": item.appVersion,
            "extracted_text": item.extractedText,
            "summary": item.summary,
            "error": item.error,
            # "or 0": the counters are int | None for leniency — a null means "unknown", stored
            # as 0 (the field default), which also keeps _cost_usd safe below.
            "prompt_tokens": item.promptTokens or 0,
            "output_tokens": item.outputTokens or 0,
            "total_tokens": item.totalTokens or 0,
            "thoughts_tokens": item.thoughtsTokens or 0,
            "cached_tokens": item.cachedTokens or 0,
            "image_tokens": item.imageTokens or 0,
            "gemini_calls": item.geminiCalls or 0,
            "model_version": item.modelVersion,
            # Recomputed here with this server's configured prices — the client never sends cost.
            "est_cost_usd": _cost_usd(item.promptTokens or 0, item.outputTokens or 0),
            "report_id": item.reportId,
            "via": "worker",
            "received_at": received_at,
        }
        (accepted if insert_usage_report(row) else duplicate).append(item.reportId)

    print(
        f"[report-usage] user={user_id} accepted={len(accepted)} duplicate={len(duplicate)}",
        flush=True,
    )
    return {"accepted": accepted, "duplicate": duplicate}


async def _cleanup_article(raw_text: str) -> tuple[str, str]:
    """Second Gemini pass: rewrite raw transcription into a clean article.

    Returns (cleaned_article, raw_response_json). The raw response is returned even when the body
    can't be parsed, so the caller can still bill the tokens it consumed; it's '' only when no
    Gemini call was made (no key / network error / HTTP error).
    """
    if not GEMINI_API_KEY:
        return "", ""
    body = {
        "contents": [{"parts": [{"text": f"{CLEANUP_PROMPT}\n\n---\n{raw_text}"}]}],
        "generationConfig": {
            "thinkingConfig": {"thinkingBudget": 0},
            "maxOutputTokens": 8192,
        },
    }
    try:
        async with httpx.AsyncClient(timeout=90.0) as client:
            resp = await client.post(
                GEMINI_ENDPOINT,
                headers={"Content-Type": "application/json", "x-goog-api-key": GEMINI_API_KEY},
                json=body,
            )
    except httpx.HTTPError as exc:
        print(f"[cleanup] network error: {exc}", flush=True)
        return "", ""
    if resp.status_code // 100 != 2:
        print(f"[cleanup] HTTP {resp.status_code}: {resp.text[:200]}", flush=True)
        return "", ""
    try:
        data = json.loads(resp.text)
        return str(data["candidates"][0]["content"]["parts"][0]["text"]).strip(), resp.text
    except (json.JSONDecodeError, KeyError, IndexError, TypeError):
        print(f"[cleanup] unparseable response: {resp.text[:200]}", flush=True)
        return "", resp.text


def _parse_gemini(raw: str) -> tuple[str | None, str, str, bool | None]:
    """Returns (extractedText, summary, heading, hasText).

    extractedText is None if the shape is wrong (transient — caller may retry). hasText is the
    model's verdict on whether the image contains transcribable text: True/False when stated, or
    None when it couldn't be determined.

    The model emits hasText + heading + summary before the (potentially long) transcription, so if
    the output is truncated at the token limit the JSON won't parse strictly. In that case we
    salvage the complete leading fields with a regex fallback instead of throwing the whole result
    away.
    """
    try:
        data = json.loads(raw)
        text = data["candidates"][0]["content"]["parts"][0]["text"]
    except (json.JSONDecodeError, KeyError, IndexError, TypeError):
        return None, "", "", None

    try:
        inner = json.loads(text)
        return (
            str(inner.get("extractedText", "")).strip(),
            str(inner.get("summary", "")).strip(),
            str(inner.get("heading", "")).strip(),
            _coerce_bool(inner.get("hasText")),
        )
    except json.JSONDecodeError:
        pass

    # Truncated/malformed JSON: pull out whatever complete fields we can.
    extracted = _json_string_field(text, "extractedText")
    summary = _json_string_field(text, "summary")
    heading = _json_string_field(text, "heading")
    has_text = _json_bool_field(text, "hasText")
    if not extracted and not summary and not heading and has_text is None:
        return None, "", "", None
    return extracted, summary, heading, has_text


def _json_string_field(text: str, name: str) -> str:
    """Extracts one JSON string field's value from possibly-truncated text. '' if absent/incomplete."""
    match = re.search(rf'"{name}"\s*:\s*"((?:[^"\\]|\\.)*)"', text)
    if not match:
        return ""
    try:
        return json.loads(f'"{match.group(1)}"').strip()  # unescape via JSON
    except json.JSONDecodeError:
        return ""


def _coerce_bool(value) -> bool | None:
    """Best-effort bool from the model's hasText field (accepts real bools or "true"/"false")."""
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        v = value.strip().lower()
        if v == "true":
            return True
        if v == "false":
            return False
    return None


def _json_bool_field(text: str, name: str) -> bool | None:
    """Extracts a JSON boolean field from possibly-truncated text. None if absent."""
    match = re.search(rf'"{name}"\s*:\s*(true|false)', text, re.IGNORECASE)
    if not match:
        return None
    return match.group(1).lower() == "true"


def _usage_from_response(raw: str) -> dict:
    """Pulls token usage (and the resolved model version) from a Gemini response. {} if absent."""
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    meta = data.get("usageMetadata") or {}

    def modality(details, name: str) -> int:
        return sum(
            int(d.get("tokenCount", 0) or 0)
            for d in (details or [])
            if str(d.get("modality", "")).upper() == name
        )

    return {
        "prompt_tokens": int(meta.get("promptTokenCount", 0) or 0),
        "output_tokens": int(meta.get("candidatesTokenCount", 0) or 0),
        "total_tokens": int(meta.get("totalTokenCount", 0) or 0),
        "thoughts_tokens": int(meta.get("thoughtsTokenCount", 0) or 0),
        "cached_tokens": int(meta.get("cachedContentTokenCount", 0) or 0),
        "image_tokens": modality(meta.get("promptTokensDetails"), "IMAGE"),
        "model_version": data.get("modelVersion"),
    }


_USAGE_SUM_KEYS = (
    "prompt_tokens", "output_tokens", "total_tokens",
    "thoughts_tokens", "cached_tokens", "image_tokens",
)


def _new_usage_acc() -> dict:
    acc = {k: 0 for k in _USAGE_SUM_KEYS}
    acc["gemini_calls"] = 0
    acc["model_version"] = None
    return acc


def _accumulate_usage(acc: dict, raw: str) -> None:
    """Adds one Gemini response's token usage into the per-request accumulator [acc]."""
    usage = _usage_from_response(raw)
    if not usage:
        return
    for key in _USAGE_SUM_KEYS:
        acc[key] += usage.get(key, 0)
    acc["gemini_calls"] += 1
    if usage.get("model_version"):
        acc["model_version"] = usage["model_version"]


def _cost_usd(prompt_tokens: int, output_tokens: int) -> float:
    """Estimated USD cost from input/output token counts and the configured per-1M prices."""
    return round(
        prompt_tokens / 1_000_000 * GEMINI_PRICE_INPUT_PER_M
        + output_tokens / 1_000_000 * GEMINI_PRICE_OUTPUT_PER_M,
        6,
    )


def _record_usage(request: Request, acc: dict) -> None:
    """Merges the accumulated token usage + estimated cost into the request's audit-log row."""
    request.state.log_extra.update(
        prompt_tokens=acc["prompt_tokens"],
        output_tokens=acc["output_tokens"],
        total_tokens=acc["total_tokens"],
        thoughts_tokens=acc["thoughts_tokens"],
        cached_tokens=acc["cached_tokens"],
        image_tokens=acc["image_tokens"],
        gemini_calls=acc["gemini_calls"],
        model_version=acc.get("model_version"),
        est_cost_usd=_cost_usd(acc["prompt_tokens"], acc["output_tokens"]),
    )


def _gemini_error(raw: str, status: int) -> str:
    try:
        return json.loads(raw)["error"]["message"]
    except (json.JSONDecodeError, KeyError, TypeError):
        return f"Gemini request failed (HTTP {status})"


# Map HTTPException detail -> {"error": ...} so the app gets a consistent JSON error shape.
@app.exception_handler(HTTPException)
async def http_exception_handler(_request, exc: HTTPException):
    return JSONResponse(status_code=exc.status_code, content={"error": exc.detail})
