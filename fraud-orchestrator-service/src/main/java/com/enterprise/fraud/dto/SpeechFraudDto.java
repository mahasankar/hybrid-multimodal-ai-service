package com.enterprise.fraud.dto;

import com.enterprise.fraud.dto.FraudScoringDto.ScoreResponse;
import com.enterprise.fraud.dto.ReceiptFraudDto.OcrExtractionResult;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class SpeechFraudDto {

    public record SpeechTranscriptionResult(
            String filename,
            String transcript,
            String language,
            @JsonProperty("duration_seconds") Double durationSeconds,
            @JsonProperty("asr_engine") String asrEngine,
            @JsonProperty("model_version") String modelVersion
    ) {}

    public record VoiceCrossCheckResult(
            @JsonProperty("spoken_amount") Double spokenAmount,
            @JsonProperty("reference_amount") Double referenceAmount,
            @JsonProperty("amount_matches") Boolean amountMatches,
            @JsonProperty("spoken_merchant_matched") Boolean spokenMerchantMatched,
            @JsonProperty("voice_discrepancy") boolean voiceDiscrepancy,
            @JsonProperty("cross_check_notes") List<String> crossCheckNotes
    ) {}

    public record MultimodalFraudEvaluationResponse(
            @JsonProperty("resolved_claim_amount") double resolvedClaimAmount,
            @JsonProperty("amount_source") String amountSource,
            @JsonProperty("ocr_result") OcrExtractionResult ocrResult,
            @JsonProperty("speech_result") SpeechTranscriptionResult speechResult,
            @JsonProperty("voice_cross_check") VoiceCrossCheckResult voiceCrossCheck,
            @JsonProperty("fraud_assessment") ScoreResponse fraudAssessment,
            @JsonProperty("review_flag") String reviewFlag
    ) {}
}