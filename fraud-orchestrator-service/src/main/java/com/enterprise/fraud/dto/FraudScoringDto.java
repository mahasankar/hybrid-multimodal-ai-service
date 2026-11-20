package com.enterprise.fraud.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

public class FraudScoringDto {

    public record ClaimRequest(
        @JsonProperty("claim_amount")
        @DecimalMin(value = "0.01", message = "Claim amount must be greater than zero")
        double claimAmount,

        @JsonProperty("amount_to_role_avg_ratio")
        double amountToRoleAvgRatio,

        @JsonProperty("employee_tenure_months")
        @Min(value = 0, message = "Tenure cannot be negative")
        int employeeTenureMonths,

        @JsonProperty("claims_last_30_days")
        @Min(value = 0, message = "Claims count cannot be negative")
        int claimsLast30Days,

        @JsonProperty("is_weekend_submission")
        int isWeekendSubmission, // 0 or 1

        @JsonProperty("is_high_risk_category")
        int isHighRiskCategory, // 0 or 1

        @JsonProperty("receipt_text_confidence")
        @DecimalMin(value = "0.0", message = "Confidence must be between 0 and 1")
        @DecimalMax(value = "1.0", message = "Confidence must be between 0 and 1")
        double receiptTextConfidence
    ) {}

    public record ScoreResponse(
        @JsonProperty("fraud_probability") double fraudProbability,
        @JsonProperty("risk_level") String riskLevel,
        @JsonProperty("model_version") String modelVersion
    ) {}
}