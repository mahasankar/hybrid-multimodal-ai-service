package com.enterprise.fraud.service;

import com.enterprise.fraud.client.FastAPIClient;
import com.enterprise.fraud.client.OcrExtractionClient;
import com.enterprise.fraud.client.SpeechTranscriptionClient;
import com.enterprise.fraud.dto.FraudScoringDto.ClaimRequest;
import com.enterprise.fraud.dto.FraudScoringDto.ScoreResponse;
import com.enterprise.fraud.dto.ReceiptFraudDto.OcrExtractionResult;
import com.enterprise.fraud.dto.ReceiptFraudDto.ReceiptClaimContext;
import com.enterprise.fraud.dto.ReceiptFraudDto.ReceiptFraudEvaluationResponse;
import com.enterprise.fraud.dto.SpeechFraudDto.MultimodalFraudEvaluationResponse;
import com.enterprise.fraud.dto.SpeechFraudDto.SpeechTranscriptionResult;
import com.enterprise.fraud.dto.SpeechFraudDto.VoiceCrossCheckResult;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ReceiptFraudOrchestrationService {

    private static final Set<String> ALLOWED_IMAGE_MIME_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "image/tiff"
    );
    private static final Set<String> ALLOWED_AUDIO_MIME_TYPES = Set.of(
            "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/wave",
            "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/ogg", "audio/webm", "audio/flac"
    );
    private static final Set<String> ALLOWED_AUDIO_EXTENSIONS = Set.of(
            ".mp3", ".wav", ".m4a", ".mp4", ".ogg", ".webm", ".flac"
    );
    private static final Set<String> MERCHANT_STOP_WORDS = Set.of(
            "the", "and", "for", "cafe", "coffee", "shop", "store", "inc", "llc", "co", "corp", "restaurant", "bar"
    );
    private static final long MAX_BYTES = 10L * 1024L * 1024L;
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.60;
    private static final double AMOUNT_TOLERANCE = 0.05;
    private static final Pattern DOLLAR_AMOUNT_PATTERN = Pattern.compile("\\$\\s*(\\d+(?:\\.\\d{1,2})?)");

    private final OcrExtractionClient ocrClient;
    private final SpeechTranscriptionClient speechClient;
    private final FastAPIClient scoringClient;

    public ReceiptFraudOrchestrationService(
            OcrExtractionClient ocrClient,
            SpeechTranscriptionClient speechClient,
            FastAPIClient scoringClient) {
        this.ocrClient = ocrClient;
        this.speechClient = speechClient;
        this.scoringClient = scoringClient;
    }

    public ReceiptFraudEvaluationResponse evaluateReceiptClaim(
            MultipartFile file,
            ReceiptClaimContext context) {

        validateReceiptAndContext(file, context);

        OcrExtractionResult ocrResult = ocrClient.extractReceipt(file);
        ResolvedAmount resolved = resolveClaimAmount(context, ocrResult);
        double ratio = resolveRatio(context, resolved.amount());

        ClaimRequest scoringRequest = new ClaimRequest(
                resolved.amount(),
                ratio,
                context.employeeTenureMonths(),
                context.claimsLast30Days(),
                context.isWeekendSubmission(),
                context.isHighRiskCategory(),
                ocrResult.receiptTextConfidence()
        );

        ScoreResponse scoreResponse = scoringClient.scoreClaim(scoringRequest);
        String reviewFlag = determineBaseReviewFlag(context, ocrResult);

        return new ReceiptFraudEvaluationResponse(
                resolved.amount(),
                resolved.source(),
                ocrResult,
                scoreResponse,
                reviewFlag
        );
    }

    public MultimodalFraudEvaluationResponse evaluateMultimodalClaim(
            MultipartFile receiptFile,
            MultipartFile audioFile,
            ReceiptClaimContext context) {

        validateReceiptAndContext(receiptFile, context);
        boolean hasAudio = audioFile != null && !audioFile.isEmpty();
        if (audioFile != null && audioFile.isEmpty()) {
            throw new IllegalArgumentException("Uploaded audio file must not be empty.");
        }
        if (hasAudio) {
            validateAudioFile(audioFile);
        }

        OcrExtractionResult ocrResult = ocrClient.extractReceipt(receiptFile);
        SpeechTranscriptionResult speechResult = hasAudio ? speechClient.transcribeAudio(audioFile) : null;

        ResolvedAmount resolved = resolveClaimAmount(context, ocrResult);
        double ratio = resolveRatio(context, resolved.amount());

        ClaimRequest scoringRequest = new ClaimRequest(
                resolved.amount(),
                ratio,
                context.employeeTenureMonths(),
                context.claimsLast30Days(),
                context.isWeekendSubmission(),
                context.isHighRiskCategory(),
                ocrResult.receiptTextConfidence()
        );

        ScoreResponse scoreResponse = scoringClient.scoreClaim(scoringRequest);

        VoiceCrossCheckResult crossCheck = speechResult != null
                ? crossCheckVoiceAgainstReceipt(speechResult.transcript(), ocrResult, resolved.amount())
                : null;

        String baseFlag = determineBaseReviewFlag(context, ocrResult);
        String finalFlag = combineReviewFlag(baseFlag, crossCheck);

        return new MultimodalFraudEvaluationResponse(
                resolved.amount(),
                resolved.source(),
                ocrResult,
                speechResult,
                crossCheck,
                scoreResponse,
                finalFlag
        );
    }

    private void validateReceiptAndContext(MultipartFile file, ReceiptClaimContext context) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Receipt file is required and must not be empty.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("Receipt file exceeds maximum allowed size of 10 MB.");
        }
        String contentType = file.getContentType();
        if (contentType != null && !ALLOWED_IMAGE_MIME_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unsupported receipt file type: " + contentType);
        }
        if (context == null) {
            throw new IllegalArgumentException("Claim context is required.");
        }
        if (context.amountToRoleAvgRatio() == null && context.roleAvgAmount() == null) {
            throw new IllegalArgumentException(
                    "Provide either 'amount_to_role_avg_ratio' or 'role_avg_amount' in claim context."
            );
        }
    }

    private void validateAudioFile(MultipartFile audioFile) {
        if (audioFile.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("Audio file exceeds maximum allowed size of 10 MB.");
        }
        String contentType = audioFile.getContentType();
        String normalizedType = contentType != null
                ? contentType.toLowerCase(Locale.ROOT).split(";")[0].trim()
                : "";
        String filename = audioFile.getOriginalFilename() != null
                ? audioFile.getOriginalFilename().toLowerCase(Locale.ROOT)
                : "";
        boolean extMatches = ALLOWED_AUDIO_EXTENSIONS.stream().anyMatch(filename::endsWith);
        boolean typeMatches = !normalizedType.isEmpty() && ALLOWED_AUDIO_MIME_TYPES.contains(normalizedType);

        if (!typeMatches && !extMatches) {
            throw new IllegalArgumentException("Unsupported audio file type: " + contentType);
        }
    }

    private ResolvedAmount resolveClaimAmount(ReceiptClaimContext context, OcrExtractionResult ocrResult) {
        if (context.claimAmount() != null) {
            return new ResolvedAmount(context.claimAmount(), "PROVIDED_CLAIM_AMOUNT");
        }
        if (ocrResult.extractedAmount() != null && ocrResult.extractedAmount() > 0.0) {
            return new ResolvedAmount(ocrResult.extractedAmount(), "OCR_EXTRACTED_AMOUNT");
        }
        throw new IllegalArgumentException(
                "No valid claim amount provided and OCR could not detect a total amount from the receipt."
        );
    }

    private double resolveRatio(ReceiptClaimContext context, double resolvedAmount) {
        if (context.amountToRoleAvgRatio() != null) {
            return context.amountToRoleAvgRatio();
        }
        return resolvedAmount / context.roleAvgAmount();
    }

    private String determineBaseReviewFlag(ReceiptClaimContext context, OcrExtractionResult ocrResult) {
        boolean lowConfidence = ocrResult.receiptTextConfidence() < LOW_CONFIDENCE_THRESHOLD;
        boolean amountMismatch = context.claimAmount() != null
                && ocrResult.extractedAmount() != null
                && Math.abs(context.claimAmount() - ocrResult.extractedAmount()) > AMOUNT_TOLERANCE;

        if (lowConfidence && amountMismatch) {
            return "MANUAL_REVIEW_LOW_OCR_CONFIDENCE_AND_AMOUNT_MISMATCH";
        }
        if (lowConfidence) {
            return "MANUAL_REVIEW_LOW_OCR_CONFIDENCE";
        }
        if (amountMismatch) {
            return "MANUAL_REVIEW_AMOUNT_MISMATCH";
        }
        return "AUTO_PIPELINE_CLEAR";
    }

    private VoiceCrossCheckResult crossCheckVoiceAgainstReceipt(
            String transcript,
            OcrExtractionResult ocrResult,
            double resolvedAmount) {

        List<String> notes = new ArrayList<>();
        Double spokenAmount = extractSpokenAmount(transcript);
        Double referenceAmount = ocrResult.extractedAmount() != null
                ? ocrResult.extractedAmount()
                : resolvedAmount;

        Boolean amountMatches = null;
        boolean amountDiscrepancy = false;

        if (spokenAmount != null && referenceAmount != null) {
            amountMatches = Math.abs(spokenAmount - referenceAmount) <= AMOUNT_TOLERANCE;
            if (!amountMatches) {
                amountDiscrepancy = true;
                notes.add(String.format(
                        Locale.ROOT,
                        "Spoken amount $%.2f differs from reference receipt/claim amount $%.2f.",
                        spokenAmount,
                        referenceAmount
                ));
            } else {
                notes.add(String.format(
                        Locale.ROOT,
                        "Spoken amount $%.2f matches reference amount $%.2f.",
                        spokenAmount,
                        referenceAmount
                ));
            }
        } else {
            notes.add("No explicit dollar amount detected in voice transcript.");
        }

        Boolean merchantMatched = checkMerchantMention(transcript, ocrResult.merchantName());
        boolean merchantDiscrepancy = false;

        if (merchantMatched != null) {
            if (merchantMatched) {
                notes.add("Spoken transcript aligns with receipt merchant '" + ocrResult.merchantName() + "'.");
            } else {
                merchantDiscrepancy = true;
                notes.add("Spoken transcript did not match receipt merchant '" + ocrResult.merchantName() + "'.");
            }
        } else {
            notes.add("Receipt merchant unavailable for voice comparison.");
        }

        boolean voiceDiscrepancy = amountDiscrepancy || merchantDiscrepancy;
        return new VoiceCrossCheckResult(
                spokenAmount,
                referenceAmount,
                amountMatches,
                merchantMatched,
                voiceDiscrepancy,
                notes
        );
    }

    private Double extractSpokenAmount(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return null;
        }
        Matcher matcher = DOLLAR_AMOUNT_PATTERN.matcher(transcript);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean checkMerchantMention(String transcript, String merchantName) {
        if (transcript == null || transcript.isBlank() || merchantName == null || merchantName.isBlank()) {
            return null;
        }
        String normalizedTranscript = transcript.toLowerCase(Locale.ROOT);
        String normalizedMerchant = merchantName.toLowerCase(Locale.ROOT).trim();

        if (normalizedTranscript.contains(normalizedMerchant)) {
            return true;
        }

        List<String> merchantTokens = Arrays.stream(normalizedMerchant.split("[^a-z0-9]+"))
                .filter(token -> token.length() >= 3 && !MERCHANT_STOP_WORDS.contains(token))
                .collect(Collectors.toList());

        if (merchantTokens.isEmpty()) {
            return null;
        }
        return merchantTokens.stream().anyMatch(normalizedTranscript::contains);
    }

    private String combineReviewFlag(String baseFlag, VoiceCrossCheckResult crossCheck) {
        if (crossCheck == null || !crossCheck.voiceDiscrepancy()) {
            return baseFlag;
        }
        if ("AUTO_PIPELINE_CLEAR".equals(baseFlag)) {
            return "MANUAL_REVIEW_VOICE_MISMATCH";
        }
        return baseFlag + "_AND_VOICE_MISMATCH";
    }

    private record ResolvedAmount(double amount, String source) {}
}