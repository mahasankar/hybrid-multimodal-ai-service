package com.enterprise.fraud.controller;

import com.enterprise.fraud.client.FastAPIClient;
import com.enterprise.fraud.dto.FraudScoringDto.ClaimRequest;
import com.enterprise.fraud.dto.FraudScoringDto.ScoreResponse;
import com.enterprise.fraud.dto.ReceiptFraudDto.ReceiptClaimContext;
import com.enterprise.fraud.dto.ReceiptFraudDto.ReceiptFraudEvaluationResponse;
import com.enterprise.fraud.dto.SpeechFraudDto.MultimodalFraudEvaluationResponse;
import com.enterprise.fraud.service.ReceiptFraudOrchestrationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/claims")
public class FraudOrchestratorController {

    private final FastAPIClient fastapiClient;
    private final ReceiptFraudOrchestrationService receiptOrchestrationService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public FraudOrchestratorController(
            FastAPIClient fastapiClient,
            ReceiptFraudOrchestrationService receiptOrchestrationService,
            ObjectMapper objectMapper,
            Validator validator) {
        this.fastapiClient = fastapiClient;
        this.receiptOrchestrationService = receiptOrchestrationService;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @PostMapping("/evaluate")
    public ResponseEntity<ScoreResponse> evaluateClaim(@Valid @RequestBody ClaimRequest request) {
        ScoreResponse response = fastapiClient.scoreClaim(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/evaluate-receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReceiptFraudEvaluationResponse> evaluateReceiptClaim(
            @RequestPart("file") MultipartFile file,
            @RequestPart("context") String contextJson) {

        ReceiptClaimContext context = parseAndValidateContext(contextJson);
        ReceiptFraudEvaluationResponse response =
                receiptOrchestrationService.evaluateReceiptClaim(file, context);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/evaluate-multimodal", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MultimodalFraudEvaluationResponse> evaluateMultimodalClaim(
            @RequestPart("receipt") MultipartFile receipt,
            @RequestPart(value = "audio", required = false) MultipartFile audio,
            @RequestPart("context") String contextJson) {

        ReceiptClaimContext context = parseAndValidateContext(contextJson);
        MultimodalFraudEvaluationResponse response =
                receiptOrchestrationService.evaluateMultimodalClaim(receipt, audio, context);
        return ResponseEntity.ok(response);
    }

    private ReceiptClaimContext parseAndValidateContext(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            throw new IllegalArgumentException("Multipart part 'context' must not be empty.");
        }
        ReceiptClaimContext context;
        try {
            context = objectMapper.readValue(contextJson, ReceiptClaimContext.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON in 'context' part: " + e.getOriginalMessage(), e);
        }

        Set<ConstraintViolation<ReceiptClaimContext>> violations = validator.validate(context);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException(details);
        }
        return context;
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestPartException.class
    })
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception ex) {
        Map<String, String> body = Map.of(
                "error", "VALIDATION_ERROR",
                "message", ex.getMessage() != null ? ex.getMessage() : "Invalid request"
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationErrors(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        Map<String, String> body = Map.of(
                "error", "VALIDATION_ERROR",
                "message", details.isEmpty() ? "Validation failed" : details
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleDownstreamError(IllegalStateException ex) {
        Map<String, String> body = Map.of(
                "error", "DOWNSTREAM_SERVICE_ERROR",
                "message", ex.getMessage() != null ? ex.getMessage() : "Downstream service error"
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}