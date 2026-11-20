package com.enterprise.fraud.dto;

import com.enterprise.fraud.dto.FraudScoringDto.ScoreResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public class ReceiptFraudDto {

    /**
     * Contextual claim metadata submitted alongside the receipt image.
     * If claimAmount is omitted or null, the orchestrator uses extracted_amount from OCR.
     */
    public record ReceiptClaimContext(
        @JsonProperty("claim_amount")
        @DecimalMin(value = "0.01", message = "Claim amount must be greater than zero")
        Double claimAmount,

        @JsonProperty("amount_to_role_avg_ratio")
        @DecimalMin(value = "0.0", message = "Amount-to-role average ratio cannot be negative")
        Double amountToRoleAvgRatio,

        @JsonProperty("role_avg_amount")
        @DecimalMin(value = "0.01", message = "Role average amount must be greater than zero")
        Double roleAvgAmount,

        @JsonProperty("employee_tenure_months")
        @Min(value = 0, message = "Tenure cannot be negative")
        int employeeTenureMonths,

        @JsonProperty("claims_last_30_days")
        @Min(value = 0, message = "Claims count cannot be negative")
        int claimsLast30Days,

        @JsonProperty("is_weekend_submission")
        @Min(0) @Max(1)
        int isWeekendSubmission,

        @JsonProperty("is_high_risk_category")
        @Min(0) @Max(1)
        int isHighRiskCategory
    ) {}

    public record OcrExtractionResult(
        String filename,
        @JsonProperty("raw_text") String rawText,
        @JsonProperty("merchant_name") String merchantName,
        @JsonProperty("extracted_amount") Double extractedAmount,
        @JsonProperty("receipt_text_confidence") double receiptTextConfidence,
        @JsonProperty("ocr_engine") String ocrEngine
    ) {}

    public record ReceiptFraudEvaluationResponse(
        @JsonProperty("resolved_claim_amount") double resolvedClaimAmount,
        @JsonProperty("amount_source") String amountSource,
        @JsonProperty("ocr_result") OcrExtractionResult ocrResult,
        @JsonProperty("fraud_assessment") ScoreResponse fraudAssessment,
        @JsonProperty("review_flag") String reviewFlag
    ) {}
}