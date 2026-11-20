import io
import os
import re
from PIL import Image, ImageOps, UnidentifiedImageError
import pytesseract


class OcrProcessingError(Exception):
    """Raised when an uploaded image cannot be decoded or processed by OCR."""


class ReceiptOcrEngine:
    DECIMAL_AMOUNT_PATTERN = re.compile(
        r"[$€₹£]?\s*(\d{1,3}(?:,\d{3})*\.\d{2}|\d+\.\d{2})"
    )
    GENERIC_HEADERS = {
        "receipt",
        "tax invoice",
        "invoice",
        "cash memo",
        "duplicate",
        "original",
    }

    def __init__(self) -> None:
        tesseract_cmd = os.getenv("TESSERACT_CMD")
        if tesseract_cmd:
            pytesseract.pytesseract.tesseract_cmd = tesseract_cmd

    def extract_from_bytes(self, file_bytes: bytes, filename: str) -> dict:
        try:
            image = Image.open(io.BytesIO(file_bytes))
            # Normalize orientation and convert to grayscale for cleaner OCR
            image = ImageOps.exif_transpose(image).convert("L")
        except (UnidentifiedImageError, OSError) as exc:
            raise OcrProcessingError(f"Unsupported or corrupted image '{filename}'") from exc

        try:
            ocr_data = pytesseract.image_to_data(image, output_type=pytesseract.Output.DICT)
        except pytesseract.TesseractNotFoundError as exc:
            raise OcrProcessingError("Tesseract OCR binary is not installed or not on PATH") from exc
        except Exception as exc:
            raise OcrProcessingError(f"OCR execution failed: {exc}") from exc

        lines = self._group_lines(ocr_data)
        words: list[str] = []
        confidences: list[float] = []

        texts = ocr_data.get("text", [])
        confs = ocr_data.get("conf", [])

        for raw_word, raw_conf in zip(texts, confs):
            cleaned = (raw_word or "").strip()
            try:
                conf_val = float(raw_conf)
            except (TypeError, ValueError):
                conf_val = -1.0

            if cleaned:
                words.append(cleaned)
                if conf_val >= 0.0:
                    confidences.append(conf_val / 100.0)

        raw_text = " ".join(words).strip()
        structured_text = "\n".join(lines.values())
        confidence = round(sum(confidences) / len(confidences), 4) if confidences else 0.0
        confidence = max(0.0, min(1.0, confidence))

        merchant_name = self._extract_merchant(lines)
        extracted_amount = self._extract_amount(structured_text, raw_text)

        return {
            "filename": filename,
            "raw_text": raw_text,
            "merchant_name": merchant_name,
            "extracted_amount": extracted_amount,
            "receipt_text_confidence": confidence,
            "ocr_engine": "tesseract",
        }

    def _group_lines(self, ocr_data: dict) -> dict[tuple[int, int, int], str]:
        grouped: dict[tuple[int, int, int], list[str]] = {}

        texts = ocr_data.get("text", [])
        blocks = ocr_data.get("block_num", [])
        pars = ocr_data.get("par_num", [])
        line_nums = ocr_data.get("line_num", [])

        for raw_word, block_no, par_no, line_no in zip(texts, blocks, pars, line_nums):
            cleaned = (raw_word or "").strip()
            if not cleaned:
                continue
            key = (
                int(block_no) if block_no is not None else 0,
                int(par_no) if par_no is not None else 0,
                int(line_no) if line_no is not None else 0,
            )
            grouped.setdefault(key, []).append(cleaned)

        return {key: " ".join(tokens).strip() for key, tokens in grouped.items() if tokens}

    def _extract_merchant(self, lines: dict[tuple[int, int, int], str]) -> str | None:
        # Inspect the first several distinct lines in reading order
        for line in list(lines.values())[:6]:
            candidate = re.sub(r"\s+", " ", line).strip()
            if len(candidate) < 3:
                continue
            if re.fullmatch(r"[\d\W]+", candidate):
                continue
            if candidate.lower() in self.GENERIC_HEADERS:
                continue
            # Skip lines that look like street addresses or totals
            if re.search(
                r"\b(street|st\.?|avenue|ave\.?|road|rd\.?|blvd|total)\b",
                candidate,
                re.IGNORECASE,
            ):
                continue
            return candidate[:120]
        return None

    def _extract_amount(self, structured_text: str, raw_text: str) -> float | None:
        search_space = structured_text or raw_text
        if not search_space:
            return None

        # 1. Prefer explicit decimal amounts on lines mentioning "total" / "amount due" / "balance due"
        labeled_decimal_values: list[float] = []
        for line in search_space.splitlines():
            if re.search(
                r"\b(?:grand\s*total|total\s*amount|amount\s*due|balance\s*due|total)\b",
                line,
                re.IGNORECASE,
            ):
                for match in self.DECIMAL_AMOUNT_PATTERN.finditer(line):
                    val = float(match.group(1).replace(",", ""))
                    if val > 0:
                        labeled_decimal_values.append(val)

        if labeled_decimal_values:
            return max(labeled_decimal_values)

        # 2. Fallback to any decimal amount across the receipt (largest is typically the total)
        all_decimal_values: list[float] = []
        for match in self.DECIMAL_AMOUNT_PATTERN.finditer(search_space):
            val = float(match.group(1).replace(",", ""))
            if val > 0:
                all_decimal_values.append(val)

        return max(all_decimal_values) if all_decimal_values else None