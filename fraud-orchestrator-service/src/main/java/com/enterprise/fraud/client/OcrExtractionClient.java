package com.enterprise.fraud.client;

import com.enterprise.fraud.dto.ReceiptFraudDto.OcrExtractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

@Service
public class OcrExtractionClient {

    private static final Logger log = LoggerFactory.getLogger(OcrExtractionClient.class);
    private final RestClient restClient;

    public OcrExtractionClient(
            RestClient.Builder restClientBuilder,
            @Value("${ocr.extraction.base-url}") String ocrServiceBaseUrl) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));

        this.restClient = restClientBuilder
                .requestFactory(requestFactory)
                .baseUrl(ocrServiceBaseUrl)
                .build();
    }

    public OcrExtractionResult extractReceipt(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            String filename = (file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank())
                    ? file.getOriginalFilename()
                    : "receipt.png";

            MediaType partMediaType = resolveMediaType(file.getContentType());

            ByteArrayResource fileResource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };

            HttpHeaders partHeaders = new HttpHeaders();
            partHeaders.setContentType(partMediaType);
            HttpEntity<ByteArrayResource> filePart = new HttpEntity<>(fileResource, partHeaders);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", filePart);

            log.info("Sending receipt '{}' ({} bytes, {}) to OCR extraction service...",
                    filename, bytes.length, partMediaType);

            OcrExtractionResult response = this.restClient.post()
                    .uri("/extract")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(OcrExtractionResult.class);

            if (response == null) {
                throw new IllegalStateException("OCR service returned an empty response body");
            }
            return response;
        } catch (IOException e) {
            log.error("Failed to read uploaded receipt bytes: {}", e.getMessage());
            throw new IllegalArgumentException("Unable to read uploaded receipt file", e);
        } catch (RestClientResponseException e) {
            log.error("OCR service returned HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode().is4xxClientError()) {
                throw new IllegalArgumentException("OCR service rejected receipt file: " + e.getResponseBodyAsString(), e);
            }
            throw new IllegalStateException("OCR Extraction Service unavailable", e);
        } catch (RestClientException e) {
            log.error("Failed to communicate with OCR extraction service: {}", e.getMessage());
            throw new IllegalStateException("OCR Extraction Service unavailable", e);
        }
    }

    private MediaType resolveMediaType(String contentType) {
        if (contentType != null && !contentType.isBlank()) {
            try {
                return MediaType.parseMediaType(contentType);
            } catch (IllegalArgumentException ignored) {
                // Fall back to octet-stream if header is malformed
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}