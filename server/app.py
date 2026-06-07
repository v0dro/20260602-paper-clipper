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
from datetime import date
from email.message import EmailMessage
from email.utils import formatdate
from pathlib import Path

import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel

load_dotenv()

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "").strip()
PROXY_TOKEN = os.getenv("PROXY_TOKEN", "").strip()
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash").strip()

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

GEMINI_ENDPOINT = (
    f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent"
)

# The newspaper-clipping prompt lives here now (moved off the device), so it can be tuned
# without rebuilding the app.
PROMPT = (
    "This is a newspaper clipping. Transcribe all readable text from it exactly, then "
    "summarize it. Respond with ONLY a JSON object with two string fields: "
    '"extractedText" (the full transcription, no commentary) and '
    '"summary" (a concise 2-3 sentence summary). Do not include any other text.'
)

app = FastAPI(title="Paper Clipper AI proxy")


class AnalyzeRequest(BaseModel):
    mimeType: str
    imageBase64: str


class AnalyzeResponse(BaseModel):
    extractedText: str
    summary: str


class FeedbackRequest(BaseModel):
    message: str
    email: str | None = None
    appVersion: str | None = None


@app.post("/feedback")
async def feedback(req: FeedbackRequest, authorization: str | None = Header(default=None)):
    if not PROXY_TOKEN or authorization != f"Bearer {PROXY_TOKEN}":
        raise HTTPException(status_code=401, detail="Invalid or missing token")
    message = req.message.strip()
    if not message:
        raise HTTPException(status_code=400, detail="message is empty")

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
    if usage_count(user_id, today) >= DAILY_LIMIT:
        raise HTTPException(
            status_code=429,
            detail=f"Daily limit reached ({DAILY_LIMIT} analyses/day). Try again tomorrow.",
        )

    # --- validate the image payload (client error before server-config error) ---
    try:
        base64.b64decode(req.imageBase64, validate=True)
    except Exception:
        raise HTTPException(status_code=400, detail="imageBase64 is not valid base64")

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
            "maxOutputTokens": 4096,
        },
    }

    # --- call Gemini, retrying transient failures (5xx / 429 / empty responses) ---
    last_error = "Gemini request failed"
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

        extracted, summary = _parse_gemini(resp.text)
        if extracted is None or (not extracted and not summary):
            last_error = "Gemini returned no usable text"
            print(f"[analyze] attempt {attempt}: empty/unparseable: {resp.text[:300]}", flush=True)
            continue

        usage_increment(user_id, today)  # count only successful analyses
        return AnalyzeResponse(extractedText=extracted, summary=summary)

    raise HTTPException(status_code=502, detail=last_error)


def _parse_gemini(raw: str) -> tuple[str | None, str]:
    """Returns (extractedText, summary). extractedText is None if the response shape is wrong."""
    try:
        data = json.loads(raw)
        text = data["candidates"][0]["content"]["parts"][0]["text"]
    except (json.JSONDecodeError, KeyError, IndexError, TypeError):
        return None, ""
    try:
        inner = json.loads(text)
    except json.JSONDecodeError:
        return None, ""
    return str(inner.get("extractedText", "")).strip(), str(inner.get("summary", "")).strip()


def _gemini_error(raw: str, status: int) -> str:
    try:
        return json.loads(raw)["error"]["message"]
    except (json.JSONDecodeError, KeyError, TypeError):
        return f"Gemini request failed (HTTP {status})"


# Map HTTPException detail -> {"error": ...} so the app gets a consistent JSON error shape.
@app.exception_handler(HTTPException)
async def http_exception_handler(_request, exc: HTTPException):
    return JSONResponse(status_code=exc.status_code, content={"error": exc.detail})
