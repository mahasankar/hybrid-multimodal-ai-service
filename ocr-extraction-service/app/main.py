from fastapi import FastAPI, File, HTTPException, UploadFile
from app.ocr_engine import OcrProcessingError, ReceiptOcrEngine
from app.schemas import OcrExtractionResponse

ALLOWED_CONTENT_TYPES = {
    "image/jpeg",
    "image/jpg",
    "image/png",
    "image/webp",
    "image/tiff",
}
MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024  # 10 MB

app = FastAPI(
    title="Receipt OCR Extraction Service",
    version="1.0.0",
    description="Extracts text, key fields, and readability confidence from expense receipts.",
)

ocr_engine = ReceiptOcrEngine()

@app.get("/health")
def health_check() -> dict[str, str]:
    return {"status": "ok", "service": "ocr-extraction-service", "version": "1.0.0"}


@app.post("/extract", response_model=OcrExtractionResponse)
async def extract_receipt(file: UploadFile = File(...)) -> OcrExtractionResponse:
    if file.content_type and file.content_type.lower() not in ALLOWED_CONTENT_TYPES:
        raise HTTPException(
            status_code=415,
            detail=f"Unsupported content type '{file.content_type}'. Allowed: {sorted(ALLOWED_CONTENT_TYPES)}",
        )

    payload = await file.read()
    if not payload:
        raise HTTPException(status_code=400, detail="Uploaded file is empty.")

    if len(payload) > MAX_FILE_SIZE_BYTES:
        raise HTTPException(status_code=413, detail="Receipt file exceeds maximum size of 10 MB.")

    try:
        result = ocr_engine.extract_from_bytes(payload, file.filename or "receipt.png")
        return OcrExtractionResponse(**result)
    except OcrProcessingError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc