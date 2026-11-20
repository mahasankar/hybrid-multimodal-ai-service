import logging
import os
from typing import Optional

import httpx
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from pydantic import BaseModel

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(name)s | %(message)s",
)
logger = logging.getLogger("speech-transcription-service")

GROQ_API_KEY = os.getenv("GROQ_API_KEY", "").strip()
GROQ_WHISPER_MODEL = os.getenv("GROQ_WHISPER_MODEL", "whisper-large-v3-turbo").strip()
GROQ_API_BASE_URL = os.getenv("GROQ_API_BASE_URL", "https://api.groq.com/openai/v1").rstrip("/")
MAX_AUDIO_BYTES = int(os.getenv("MAX_AUDIO_BYTES", str(10 * 1024 * 1024)))  # 10 MB cap
REQUEST_TIMEOUT_SECONDS = float(os.getenv("GROQ_TIMEOUT_SECONDS", "30.0"))

ALLOWED_CONTENT_TYPES = {
    "audio/mpeg",
    "audio/mp3",
    "audio/wav",
    "audio/x-wav",
    "audio/wave",
    "audio/mp4",
    "audio/m4a",
    "audio/x-m4a",
    "audio/ogg",
    "audio/webm",
    "audio/flac",
}

ALLOWED_EXTENSIONS = {".mp3", ".wav", ".m4a", ".mp4", ".ogg", ".webm", ".flac"}

app = FastAPI(
    title="Speech Transcription Service (Groq Whisper ASR)",
    version="1.0.0",
    description="Transcribes field adjuster or employee incident voice notes into text using Groq Whisper.",
)


class TranscriptionResponse(BaseModel):
    filename: str
    transcript: str
    language: Optional[str] = None
    duration_seconds: Optional[float] = None
    asr_engine: str
    model_version: str


def _validate_audio_upload(file: UploadFile, content: bytes) -> str:
    filename = (file.filename or "audio_note.mp3").strip()
    ext = os.path.splitext(filename)[1].lower()
    content_type = (file.content_type or "").lower().split(";")[0].strip()

    if not content:
        raise HTTPException(status_code=400, detail="Uploaded audio file is empty.")

    if len(content) > MAX_AUDIO_BYTES:
        raise HTTPException(
            status_code=400,
            detail=f"Audio file exceeds maximum allowed size of {MAX_AUDIO_BYTES} bytes.",
        )

    type_valid = content_type in ALLOWED_CONTENT_TYPES
    ext_valid = ext in ALLOWED_EXTENSIONS

    if not (type_valid or ext_valid):
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported audio file type '{content_type or ext}'. Allowed: .mp3, .wav, .m4a, .ogg, .webm, .flac.",
        )

    return filename


@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "speech-transcription-service",
        "asr_engine": "groq-whisper",
        "model": GROQ_WHISPER_MODEL,
        "api_key_configured": bool(GROQ_API_KEY),
    }


@app.post("/transcribe-audio", response_model=TranscriptionResponse)
async def transcribe_audio(
    file: UploadFile = File(...),
    language: Optional[str] = Form(default="en"),
):
    if not GROQ_API_KEY:
        logger.error("GROQ_API_KEY is not configured in environment.")
        raise HTTPException(
            status_code=503,
            detail="Speech transcription service is missing GROQ_API_KEY configuration.",
        )

    content = await file.read()
    filename = _validate_audio_upload(file, content)
    mime_type = (file.content_type or "audio/mpeg").split(";")[0].strip()

    form_data = {
        "model": GROQ_WHISPER_MODEL,
        "response_format": "verbose_json",
        "temperature": "0",
    }
    if language and language.strip():
        form_data["language"] = language.strip()

    headers = {"Authorization": f"Bearer {GROQ_API_KEY}"}
    files = {"file": (filename, content, mime_type)}

    url = f"{GROQ_API_BASE_URL}/audio/transcriptions"

    try:
        async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT_SECONDS) as client:
            response = await client.post(url, headers=headers, data=form_data, files=files)
    except httpx.TimeoutException as exc:
        logger.warning("Groq Whisper request timed out for file=%s: %s", filename, exc)
        raise HTTPException(status_code=503, detail="Upstream ASR provider timed out.")
    except httpx.RequestError as exc:
        logger.error("Network error calling Groq Whisper for file=%s: %s", filename, exc)
        raise HTTPException(status_code=503, detail="Upstream ASR provider unreachable.")

    if response.status_code == 400:
        logger.warning("Groq rejected audio payload file=%s status=400 body=%s", filename, response.text)
        raise HTTPException(status_code=400, detail="Audio file could not be decoded or transcribed.")

    if response.status_code in (401, 403):
        logger.error("Groq authentication failed status=%s", response.status_code)
        raise HTTPException(status_code=503, detail="Upstream ASR authentication failed.")

    if response.status_code == 429:
        logger.warning("Groq rate limit hit status=429")
        raise HTTPException(status_code=503, detail="Upstream ASR rate limit exceeded; retry shortly.")

    if response.status_code >= 500 or not response.is_success:
        logger.error("Groq unexpected status=%s body=%s", response.status_code, response.text)
        raise HTTPException(status_code=503, detail="Upstream ASR service error.")

    payload = response.json()
    transcript = (payload.get("text") or "").strip()

    if not transcript:
        raise HTTPException(
            status_code=400,
            detail="No speech detected in the uploaded audio note.",
        )

    detected_language = payload.get("language") or language
    raw_duration = payload.get("duration")
    duration_seconds = round(float(raw_duration), 2) if raw_duration is not None else None

    logger.info(
        "Transcribed audio file=%s duration=%ss chars=%d",
        filename,
        duration_seconds,
        len(transcript),
    )

    return TranscriptionResponse(
        filename=filename,
        transcript=transcript,
        language=detected_language,
        duration_seconds=duration_seconds,
        asr_engine="groq-whisper",
        model_version=GROQ_WHISPER_MODEL,
    )