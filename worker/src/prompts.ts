// Prompts for the two-pass Gemini flow.
//
// SOURCE OF TRUTH: server/app.py (PROMPT / CLEANUP_PROMPT). These strings are copied VERBATIM —
// the worker must produce identical analyses to the home server it stands in for. If you tune a
// prompt in server/app.py, mirror the change here (and vice versa).

// First pass: judge whether the image has readable text, then transcribe + summarize it as JSON.
export const PROMPT =
  "You are given a photograph. First determine whether it actually contains readable text to " +
  "transcribe (a newspaper clipping, a printed page, a sign, handwriting, etc.). " +
  "Respond with ONLY a JSON object with these four fields, in this exact order: " +
  '"hasText" (boolean: true ONLY if the image contains real, legible text; false for photos ' +
  "with no meaningful text, such as a car, a person, an object, food or scenery), " +
  '"heading" (a title of fewer than 5 words capturing the main topic), ' +
  '"summary" (a concise 2-3 sentence summary), and ' +
  '"extractedText" (a full, exact transcription of all readable text, no commentary). ' +
  "If hasText is false, set heading, summary and extractedText to empty strings. " +
  "Transcribe only text that is genuinely present — never invent, guess or describe text that " +
  "is not clearly legible in the image. Emit hasText, heading and summary first so they are " +
  "never lost if the transcription is long. Do not include any other text.";

// Second-pass prompt: the raw transcription above is messy (OCR errors, broken lines, stray
// captions/bylines/page furniture). This rewrites it into a clean, readable article. The raw
// first-pass transcription is discarded; only this cleaned article is shown in the app.
export const CLEANUP_PROMPT =
  "Below is raw text transcribed from a newspaper clipping. It may contain OCR errors, " +
  "words split across line breaks, hyphenation, and stray fragments such as captions, " +
  "bylines, datelines or page furniture. Rewrite it into a clean, presentable article: fix " +
  "obvious OCR mistakes, rejoin broken words and lines, drop the stray fragments, and " +
  "organize it into well-formed paragraphs. Preserve the actual content and meaning and do " +
  "NOT invent any facts. Respond with ONLY the cleaned article text, no preamble or commentary.";
