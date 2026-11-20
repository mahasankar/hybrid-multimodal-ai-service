from pydantic import BaseModel, Field

class OcrExtractionResponse(BaseModel):
    filename: str
    raw_text: str
    merchant_name: str | None = None
    extracted_amount: float | None = Field(default=None, ge=0.0)
    receipt_text_confidence: float = Field(..., ge=0.0, le=1.0)
    ocr_engine: str = "tesseract"