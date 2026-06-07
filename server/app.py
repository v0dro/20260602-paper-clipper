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

import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel

load_dotenv()

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "").strip()
PROXY_TOKEN = os.getenv("PROXY_TOKEN", "").strip()
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash").strip()

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


@app.get("/health")
async def health() -> dict:
    return {"status": "ok", "model": GEMINI_MODEL}


@app.post("/analyze", response_model=AnalyzeResponse)
async def analyze(req: AnalyzeRequest, authorization: str | None = Header(default=None)):
    # --- auth ---
    if not PROXY_TOKEN:
        raise HTTPException(status_code=500, detail="Server PROXY_TOKEN is not configured")
    expected = f"Bearer {PROXY_TOKEN}"
    if authorization != expected:
        raise HTTPException(status_code=401, detail="Invalid or missing token")

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
        "generationConfig": {"responseMimeType": "application/json"},
    }

    # --- call Gemini ---
    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            resp = await client.post(
                GEMINI_ENDPOINT,
                headers={
                    "Content-Type": "application/json",
                    "x-goog-api-key": GEMINI_API_KEY,
                },
                json=body,
            )
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"Could not reach Gemini: {exc}")

    if resp.status_code // 100 != 2:
        message = _gemini_error(resp.text, resp.status_code)
        raise HTTPException(status_code=502, detail=message)

    extracted, summary = _parse_gemini(resp.text)
    if extracted is None:
        raise HTTPException(status_code=502, detail="Unexpected response from Gemini")
    if not extracted and not summary:
        raise HTTPException(status_code=502, detail="Gemini returned no text")
    return AnalyzeResponse(extractedText=extracted, summary=summary)


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
