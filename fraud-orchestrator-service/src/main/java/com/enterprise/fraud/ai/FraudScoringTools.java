package com.enterprise.fraud.ai;

import com.enterprise.fraud.client.FastAPIClient;
import com.enterprise.fraud.dto.FraudScoringDto.ClaimRequest;
import com.enterprise.fraud.dto.FraudScoringDto.ScoreResponse;
import com.enterprise.fraud.dto.ReceiptFraudDto.ReceiptClaimContext;
import com.enterprise.fraud.dto.ReceiptFraudDto.ReceiptFraudEvaluationResponse;
import com.enterprise.fraud.service.ReceiptFraudOrchestrationService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class FraudScoringTools {

    private final FastAPIClient fastapiClient;
    private final ReceiptFraudOrchestrationService receiptOrchestrationService;

    public FraudScoringTools(
            FastAPIClient fastapiClient,
            ReceiptFraudOrchestrationService receiptOrchestrationService) {
        this.fastapiClient = fastapiClient;
        this.receiptOrchestrationService = receiptOrchestrationService;
    }

    @Tool(description = "Evaluates an employee expense claim against the XGBoost fraud detection model. " +
                         "Returns a fraud risk probability and risk level (LOW, MEDIUM, HIGH).")
    public ScoreResponse evaluateExpenseFraudRisk(ClaimRequest request) {
        return fastapiClient.scoreClaim(request);
    }

    @Tool(description = "Runs OCR extraction on a receipt image file and evaluates expense fraud risk " +
                         "using the extracted amount, OCR confidence, and employee claim context.")
    public ReceiptFraudEvaluationResponse evaluateReceiptClaimFromFile(
            String filePath,
            ReceiptClaimContext context) throws IOException {

        Path path = Path.of(filePath);
        byte[] content = Files.readAllBytes(path);
        String detectedType = Files.probeContentType(path);
        String contentType = (detectedType != null) ? detectedType : "image/png";

        MultipartFile multipartFile = new ByteArrayMultipartFile(
                "file",
                path.getFileName().toString(),
                contentType,
                content
        );

        return receiptOrchestrationService.evaluateReceiptClaim(multipartFile, context);
    }

    private record ByteArrayMultipartFile(
            String name,
            String originalFilename,
            String contentType,
            byte[] content
    ) implements MultipartFile {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), content);
        }
    }
}