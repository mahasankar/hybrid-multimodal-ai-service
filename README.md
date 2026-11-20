# Hybrid Multimodal AI Expense Fraud Orchestrator (`hybrid-multimodal-ai-service`)

A production-oriented hybrid AI and deterministic rule orchestration system for evaluating employee expense claims across tabular fraud risk signals, receipt OCR extraction, and optional spoken voice notes.

## Table of Contents

1. [Problem & Use Case](#problem--use-case)
2. [System Architecture & Service Orchestration](#system-architecture--service-orchestration)
3. [Repository Directory Structure](#repository-directory-structure)
4. [Review Flagging & Cross-Check Rules](#review-flagging--cross-check-rules)
5. [Error Handling & Resilience Contract](#error-handling--resilience-contract)
6. [Prerequisites & Environment Setup](#prerequisites--environment-setup)
7. [Building & Running All Services](#building--running-all-services)
8. [Testing Guide: Positive, Negative & Edge Cases](#testing-guide-positive-negative--edge-cases)
9. [Current Scope, Limitations & Future Enhancements](#current-scope-limitations--future-enhancements)

## Problem & Use Case

Organizations processing high volumes of employee expense reimbursements face two major operational risks:

* **Fraud & Policy Abuse:** Inflated amounts, weekend/high-risk category anomalies, repeat claim bursts, or inconsistent evidence across submitted artifacts.
* **Manual Review Bottlenecks:** Reviewing every routine low-risk claim slows finance teams down, while blindly auto-approving claims with conflicting details exposes the business to loss.

`hybrid-multimodal-ai-service` solves this by combining **machine learning risk scoring**, **vision OCR receipt extraction**, and **speech-to-text transcription** under a **Java Spring Boot orchestrator** that enforces **deterministic business cross-checks** before routing claims to `AUTO_PIPELINE_CLEAR` or targeted human review queues.

## System Architecture & Service Orchestration

The platform consists of **four cooperating services** running locally on dedicated ports:

| Service | Tech Stack | Port | Primary Responsibility |
| :--- | :--- | :--- | :--- |
| **`fraud-orchestrator-service`** | Java, Spring Boot (`RestClient`, Bean Validation) | `8080` | Public API gateway/orchestrator, multipart file handling, service chaining, deterministic voice/receipt/claim cross-checks, review flag routing, and uniform `400`/`503` error responses. |
| **`ml-scoring-service`** | Python, FastAPI (`app.main:app`), XGBoost | `8000` | Tabular fraud probability inference (`POST /score`) returning `fraud_probability`, `risk_level` (`LOW`, `MEDIUM`, `HIGH`), and `model_version`. |
| **`ocr-extraction-service`** | Python, FastAPI (`app.main:app`), Tesseract / OCR | `8001` | Receipt image parsing (`POST /extract`) returning `raw_text`, `merchant_name`, `extracted_amount`, `receipt_text_confidence`, and `ocr_engine`. |
| **`speech-transcription-service`** | Python, FastAPI (`app:app`), Groq Whisper (`whisper-large-v3-turbo`) | `8002` | Audio transcription (`POST /transcribe-audio`) returning `transcript`, `language`, `duration_seconds`, `asr_engine`, and `model_version`. |

### Architecture & Multimodal Request Flow

```text
                        +---------------------------------------+
                        | Client / Caller (REST / Multipart)    |
                        +---------------------------------------+
                                            |
                                            v
+---------------------------------------------------------------------------------------------------+
| fraud-orchestrator-service (Java / Spring Boot — Port 8080)                                       |
|                                                                                                   |
|  1. Validate multipart parts (receipt required, audio optional, context JSON)                     |
|         |                                                                                         |
|         +------------------------+------------------------------------+                             |
|         |                        | (if audio present)                 |                             |
|         v                        v                                    |                             |
|  +--------------+       +-----------------------+                     |                             |
|  | POST /extract|       | POST /transcribe-audio|                     |                             |
|  +------+-------+       +-----------+-----------+                     |                             |
|         |                           |                                 |                             |
|         v                           v                                 |                             |
|  [ocr-extraction-service]  [speech-transcription-service]             |                             |
|  (Python FastAPI :8001)    (Python FastAPI :8002 / Groq Whisper)      |                             |
|  returns merchant, amount, returns transcript, language,            |                             |
|  confidence, raw_text      duration, model_version                  |                             |
|         |                           |                                 |                             |
|         +-------------+-------------+                                 |                             |
|                       |                                               |                             |
|                       v                                               |                             |
|  4. Resolve claim amount & role-average ratio                         |                             |
|         |                                                             |                             |
|         v                                                             |                             |
|  +--------------+                                                     |                             |
|  | POST /score  |                                                     |                             |
|  +------+-------+                                                     |                             |
|         |                                                             |                             |
|         v                                                             |                             |
|  [ml-scoring-service]                                                 |                             |
|  (Python FastAPI :8000 / XGBoost)                                     |                             |
|  returns fraud_probability, risk_level, model_version                 |                             |
|         |                                                             |                             |
|         v                                                             |                             |
|  6. Deterministic Cross-Checks & Review Flag Routing                 |                             |
|     - OCR confidence (< 0.60) & claim vs. OCR amount (> $0.05)       |                             |
|     - Spoken amount vs. ref amount (> $0.05) & merchant match         |                             |
|         |                                                             |                             |
|         +------------------------+------------------------------------+                             |
|                                  |                                                                |
|                                  v                                                                |
|                     AUTO_PIPELINE_CLEAR  or  MANUAL_REVIEW_*                                      |
|                     (with full audit payload & 400/503 guardrails)                                |
+---------------------------------------------------------------------------------------------------+
```

### End-to-End Orchestration Steps (`POST /api/claims/evaluate-multimodal`)

1. **Request & Multipart Validation (`8080`):** Validates required `receipt` file, optional `audio` file (rejects empty or unsupported audio files before calling ASR), and JSON `context` payload.
2. **Receipt OCR Extraction (`8080 -> 8001`):** Sends the receipt image to `ocr-extraction-service` (`POST /extract`) to obtain merchant name, extracted amount, and OCR readability confidence.
3. **Optional Speech Transcription (`8080 -> 8002`):** If an `audio` part is present and non-empty, sends the audio file to `speech-transcription-service` (`POST /transcribe-audio`) to retrieve the spoken `transcript`. If `audio` is omitted, speech fields remain `null` and processing continues smoothly.
4. **Claim Amount & Ratio Resolution (`8080`):** Resolves `resolved_claim_amount` (using `claim_amount` from `context` when provided, or falling back to `extracted_amount` from OCR) and computes/resolves `amount_to_role_avg_ratio`.
5. **Tabular ML Risk Scoring (`8080 -> 8000`):** Sends structured claim features + `receipt_text_confidence` to `ml-scoring-service` (`POST /score`) to obtain `fraud_probability` and `risk_level`.
6. **Deterministic Cross-Checks & Review Routing (`8080`):**
   * Compares explicit `claim_amount` against OCR `extracted_amount` (tolerance: `$0.05`) and checks OCR confidence (`< 0.60` threshold).
   * When a voice transcript is present, parses spoken dollar amounts (e.g., `$45` -> `45.00`) against the reference amount (`$0.05` tolerance) and checks merchant alignment against `ocr_result.merchant_name`.
   * Combines findings into a clear `review_flag` and returns full auditability (`ocr_result`, `speech_result`, `voice_cross_check`, `fraud_assessment`).

## Repository Directory Structure

```text
hybrid-multimodal-ai-service/
├── .env.example
├── .gitignore
├── README.md
├── fraud-orchestrator-service/
│   ├── pom.xml
│   └── src/
│       └── main/
│           ├── java/
│           │   └── com/
│           │       └── enterprise/
│           │           └── fraud/
│           │               ├── FraudOrchestratorApplication.java
│           │               ├── ai/
│           │               │   └── FraudScoringTools.java
│           │               ├── client/
│           │               │   ├── FastAPIClient.java
│           │               │   ├── OcrExtractionClient.java
│           │               │   └── SpeechTranscriptionClient.java
│           │               ├── controller/
│           │               │   └── FraudOrchestratorController.java
│           │               ├── dto/
│           │               │   ├── FraudScoringDto.java
│           │               │   ├── ReceiptFraudDto.java
│           │               │   └── SpeechFraudDto.java
│           │               └── service/
│           │                   └── ReceiptFraudOrchestrationService.java
│           └── resources/
│               └── application.properties
├── ml-scoring-service/
│   ├── Dockerfile
│   ├── requirements.txt
│   ├── train_model.py
│   ├── app/
│   │   ├── main.py
│   │   ├── model.py
│   │   └── schemas.py
│   └── models/
│       ├── expense_fraud_xgb.json
│       └── model_metadata.json
├── ocr-extraction-service/
│   ├── requirements.txt
│   └── app/
│       ├── main.py
│       ├── ocr_engine.py
│       └── schemas.py
└── speech-transcription-service/
    ├── app.py
    └── requirements.txt
```

## Review Flagging & Cross-Check Rules

The orchestrator evaluates deterministic rules so reviewers receive explicit reasons when a claim cannot be auto-cleared:

| Condition / Signal | Rule Behavior | Resulting `review_flag` |
| :--- | :--- | :--- |
| Clean OCR confidence (`>= 0.60`), matching amounts (within `$0.05`), matching/absent voice discrepancy | Passes deterministic checks | `AUTO_PIPELINE_CLEAR` |
| Low OCR readability (`receipt_text_confidence < 0.60`) | Flags unclear receipt text | `MANUAL_REVIEW_LOW_OCR_CONFIDENCE` |
| Provided `claim_amount` vs. OCR `extracted_amount` differs by `> $0.05` | Flags claim-to-receipt price conflict | `MANUAL_REVIEW_AMOUNT_MISMATCH` |
| Both low OCR confidence and amount mismatch occur | Combines both flags | `MANUAL_REVIEW_LOW_OCR_CONFIDENCE_AND_AMOUNT_MISMATCH` |
| Spoken dollar amount differs from reference amount (`> $0.05`) **or** spoken merchant does not match receipt merchant | Flags voice-to-receipt conflict (`voice_discrepancy: true`) | `MANUAL_REVIEW_VOICE_MISMATCH` (or `<BASE_FLAG>_AND_VOICE_MISMATCH` when combined with an OCR/amount flag) |
| Voice note has no spoken dollar amount (`spoken_amount: null`) and merchant matches | Treated as informational note (`voice_discrepancy: false`) without false mismatch | Does not trigger voice mismatch on its own |

## Error Handling & Resilience Contract

`FraudOrchestratorController` normalizes error responses into consistent JSON payloads:

* **`400 Bad Request` (`VALIDATION_ERROR`):** Returned for invalid field values (e.g., negative `claim_amount`), missing required multipart parts (`context`, `receipt`), empty uploaded audio files, malformed JSON in `context`, or unsupported audio/image types.
* **`503 Service Unavailable` (`DOWNSTREAM_SERVICE_ERROR`):** Returned when `ml-scoring-service` (`8000`), `ocr-extraction-service` (`8001`), or `speech-transcription-service` (`8002`) is unreachable, times out, or returns a `5xx` error.

## Prerequisites & Environment Setup

### Prerequisites

* **Java 21+** and **Apache Maven 3.8+** (for `fraud-orchestrator-service`)
* **Python 3.10+** and `pip` (for `ml-scoring-service`, `ocr-extraction-service`, and `speech-transcription-service`)
* **Tesseract OCR** installed on the host machine (for `ocr-extraction-service`)
* **Groq API Key** (`GROQ_API_KEY`) for `speech-transcription-service`

### Environment Configuration

1. Copy `.env.example` to `.env` at the repository root:

   ```cmd
   copy .env.example .env
   ```

2. Populate `GROQ_API_KEY` in `.env` (and set environment variables in your shell/service runners as needed):

   ```dotenv
   GROQ_API_KEY=your_actual_groq_api_key_here
   GROQ_WHISPER_MODEL=whisper-large-v3-turbo
   ML_SCORING_URL=http://localhost:8000
   OCR_EXTRACTION_URL=http://localhost:8001
   SPEECH_TRANSCRIPTION_URL=http://localhost:8002
   ```

> **Security Note:** Never commit `.env`, real API keys, credentials, or local scratch media files to Git.

## Building & Running All Services

Start each service in its own terminal window in order so `fraud-orchestrator-service` can reach all three downstream endpoints.

### 1. Start ML Scoring Service (`Port 8000`)

```cmd
cd ml-scoring-service
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

### 2. Start OCR Extraction Service (`Port 8001`)

```cmd
cd ocr-extraction-service
python -m uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload
```

### 3. Start Speech Transcription Service (`Port 8002`)

```cmd
cd speech-transcription-service
python -m uvicorn app:app --host 0.0.0.0 --port 8002 --reload
```

### 4. Compile & Start Fraud Orchestrator Service (`Port 8080`)

```cmd
cd fraud-orchestrator-service
mvn clean compile
mvn spring-boot:run
```

## Testing Guide: Positive, Negative & Edge Cases

The commands below use Windows `curl` syntax (matching local integration verification). Replace file paths (`test_receipt.png`, `test_voice_*.mp3`, `note.txt`) with your local test files when running.

### A. Direct ML Scoring (`POST /api/claims/evaluate`)

* **Positive — Valid claim payload (`200 OK`):**

  ```cmd
  curl -s -X POST http://localhost:8080/api/claims/evaluate ^
    -H "Content-Type: application/json" ^
    -d "{\"claim_amount\":12.93,\"amount_to_role_avg_ratio\":0.52,\"employee_tenure_months\":18,\"claims_last_30_days\":1,\"is_weekend_submission\":0,\"is_high_risk_category\":0,\"receipt_text_confidence\":0.945}"
  ```

* **Negative — Invalid negative `claim_amount` (`400 VALIDATION_ERROR`):**

  ```cmd
  curl -i -s -X POST http://localhost:8080/api/claims/evaluate ^
    -H "Content-Type: application/json" ^
    -d "{\"claim_amount\":-5.0,\"amount_to_role_avg_ratio\":0.52,\"employee_tenure_months\":18,\"claims_last_30_days\":1,\"is_weekend_submission\":0,\"is_high_risk_category\":0,\"receipt_text_confidence\":0.945}"
  ```

### B. Receipt OCR + ML Pipeline (`POST /api/claims/evaluate-receipt`)

* **Positive — Receipt image + valid claim context (`200 OK`, `AUTO_PIPELINE_CLEAR`):**

  ```cmd
  curl -s -X POST http://localhost:8080/api/claims/evaluate-receipt ^
    -F "file=@test_receipt.png;type=image/png" ^
    -F "context={\"claim_amount\":12.93,\"role_avg_amount\":25.0,\"employee_tenure_months\":18,\"claims_last_30_days\":1,\"is_weekend_submission\":0,\"is_high_risk_category\":0}"
  ```

* **Negative — Missing `context` multipart part (`400 VALIDATION_ERROR`):**

  ```cmd
  curl -i -s -X POST http://localhost:8080/api/claims/evaluate-receipt ^
    -F "file=@test_receipt.png;type=image/png"
  ```

### C. Multimodal Evaluation (`POST /api/claims/evaluate-multimodal`)

* **Positive — Matching spoken amount and merchant (`200 OK`, `AUTO_PIPELINE_CLEAR`):**

  ```cmd
  curl -s -X POST http://localhost:8080/api/claims/evaluate-multimodal ^
    -F "receipt=@test_receipt.png;type=image/png" ^
    -F "audio=@test_voice_match.mp3;type=audio/mpeg" ^
    -F "context={\"claim_amount\":12.93,\"role_avg_amount\":25.0,\"employee_tenure_months\":18,\"claims_last_30_days\":1,\"is_weekend_submission\":0,\"is_high_risk_category\":0}"
  ```

* **Positive Edge Case — Optional `audio` omitted (`200 OK`, `speech_result: null`, `voice_cross_check: null`):**

  ```cmd
  curl -s -X POST http://localhost:8080/api/claims/evaluate-multimodal ^
    -F "receipt=@test_receipt.png;type=image/png" ^
    -F "context={\"claim_amount\":12.93,\"role_avg_amount\":25.0,\"employee_tenure_months\":18,\"claims_last_30_days\":1,\"is_weekend_submission\":0,\"is_high_risk_category\":0}"
  ```

* **Positive Edge Case — Voice note with no explicit dollar amount (`200 OK`, `spoken_amount: null`, `voice_discrepancy: false`):**

  ```cmd
  curl -s -X POST http://localhost:8080/api/claims/evaluate-multimodal ^
    -F "receipt=@test_receipt.png;type=image/png" ^
    -F "audio=@test_voice_no_amount.mp3;type=audio/mpeg" ^
    -F "context={\"claim_amount\":12.93,\"role_avg_amount\":25.0,\"employee_tenure_months\":18,\"claims_last_30_days\":1,\"is_weekend_submission\":0,\"is_high_risk_category\":0}"
  ```

* **Negative / Fraud Signal — Spoken amount mismatch (`MANUAL_REVIEW_VOICE_MISMATCH`):**

  ```cmd
  curl -s -X POST http://localhost:8080/api/claims/evaluate-multimodal ^
    -F "receipt=@test_receipt.png;type=image/png" ^
    -F "audio=@test_voice_mismatch.mp3;type=audio/mpeg" ^
    -F "context={\"claim_amount\":12.93,\"role_avg_amount\":25.0,\"employee_tenure_months\":18,\"claims_last_30_days\":1,\"is_weekend_submission\":0,\"is_high_risk_category\":0}"
  ```

* **Negative / Fraud Signal — Spoken merchant mismatch (`MANUAL_REVIEW_VOICE_MISMATCH`):**

  ```cmd
  curl -s -X POST http://localhost:8080/api/claims/evaluate-multimodal ^
    -F "receipt=@test_receipt.png;type=image/png" ^
    -F "audio=@test_voice_wrong_merchant.mp3;type=audio/mpeg" ^
    -F "context={\"claim_amount\":12.93,\"role_avg_amount\":25.0,\"employee_tenure_months\":18,\"claims_last_30_days\":1,\"is_weekend_submission\":0,\"is_high_risk_category\":0}"
  ```

* **Negative / Fraud Signal — Combined claim/receipt mismatch + voice mismatch (`MANUAL_REVIEW_AMOUNT_MISMATCH_AND_VOICE_MISMATCH`):**

  ```cmd
  curl -s -X POST http://localhost:8080/api/claims/evaluate-multimodal ^
    -F "receipt=@test_receipt.png;type=image/png" ^
    -F "audio=@test_voice_mismatch.mp3;type=audio/mpeg" ^
    -F "context={\"claim_amount\":20.00,\"role_avg_amount\":25.0,\"employee_tenure_months\":18,\"claims_last_30_days\":1,\"is_weekend_submission\":0,\"is_high_risk_category\":0}"
  ```

* **Negative — Empty audio file (`400 VALIDATION_ERROR`):**

  ```cmd
  curl -i -s -X POST http://localhost:8080/api/claims/evaluate-multimodal ^
    -F "receipt=@test_receipt.png;type=image/png" ^
    -F "audio=@empty_audio.mp3;type=audio/mpeg" ^
    -F "context={\"claim_amount\":12.93,\"role_avg_amount\":25.0,\"employee_tenure_months\":18,\"claims_last_30_days\":1,\"is_weekend_submission\":0,\"is_high_risk_category\":0}"
  ```

* **Negative — Missing required `receipt` part (`400 VALIDATION_ERROR`):**

  ```cmd
  curl -i -s -X POST http://localhost:8080/api/claims/evaluate-multimodal ^
    -F "audio=@test_voice_match.mp3;type=audio/mpeg" ^
    -F "context={\"claim_amount\":12.93,\"role_avg_amount\":25.0,\"employee_tenure_months\":18,\"claims_last_30_days\":1,\"is_weekend_submission\":0,\"is_high_risk_category\":0}"
  ```

* **Negative — Invalid JSON in `context` (`400 VALIDATION_ERROR`):**

  ```cmd
  curl -i -s -X POST http://localhost:8080/api/claims/evaluate-multimodal ^
    -F "receipt=@test_receipt.png;type=image/png" ^
    -F "context={invalid-json"
  ```

* **Negative — Unsupported `audio` MIME/file type (`400 VALIDATION_ERROR`):**

  ```cmd
  curl -i -s -X POST http://localhost:8080/api/claims/evaluate-multimodal ^
    -F "receipt=@test_receipt.png;type=image/png" ^
    -F "audio=@note.txt;type=text/plain" ^
    -F "context={\"claim_amount\":12.93,\"role_avg_amount\":25.0,\"employee_tenure_months\":18,\"claims_last_30_days\":1,\"is_weekend_submission\":0,\"is_high_risk_category\":0}"
  ```

* **Negative — Downstream speech ASR service (`8002`) stopped (`503 DOWNSTREAM_SERVICE_ERROR`):**
  Stop `speech-transcription-service` on port `8002`, then run:

  ```cmd
  curl -s -X POST http://localhost:8080/api/claims/evaluate-multimodal ^
    -F "receipt=@test_receipt.png;type=image/png" ^
    -F "audio=@test_voice_mismatch.mp3;type=audio/mpeg" ^
    -F "context={\"claim_amount\":12.93,\"role_avg_amount\":25.0,\"employee_tenure_months\":18,\"claims_last_30_days\":1,\"is_weekend_submission\":0,\"is_high_risk_category\":0}"
  ```

## Current Scope, Limitations & Future Enhancements

* **Merchant Matching:** Uses normalized substring and token matching (excluding common retail/restaurant stop words) rather than fuzzy edit-distance or LLM entity linking.
* **Future Enhancements:** Add Docker Compose orchestration for one-command local bring-up, add automated JUnit/MockMvc and `pytest` CI test suites, and support configurable risk thresholds via properties.