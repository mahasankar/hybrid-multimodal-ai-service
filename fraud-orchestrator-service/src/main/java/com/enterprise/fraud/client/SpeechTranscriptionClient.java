package com.enterprise.fraud.client;

import com.enterprise.fraud.dto.SpeechFraudDto.SpeechTranscriptionResult;
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
public class SpeechTranscriptionClient {

    private static final Logger log = LoggerFactory.getLogger(SpeechTranscriptionClient.class);
    private final RestClient restClient;

    public SpeechTranscriptionClient(
            RestClient.Builder restClientBuilder,
            @Value("${speech.transcription.base-url}") String speechServiceBaseUrl) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));

        this.restClient = restClientBuilder
                .requestFactory(requestFactory)
                .baseUrl(speechServiceBaseUrl)
                .build();
    }

    public SpeechTranscriptionResult transcribeAudio(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            String filename = (file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank())
                    ? file.getOriginalFilename()
                    : "voice_note.mp3";

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
            body.add("language", "en");

            log.info("Sending audio note '{}' ({} bytes, {}) to speech transcription service...",
                    filename, bytes.length, partMediaType);

            SpeechTranscriptionResult response = this.restClient.post()
                    .uri("/transcribe-audio")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(SpeechTranscriptionResult.class);

            if (response == null) {
                throw new IllegalStateException("Speech Transcription Service returned an empty response body");
            }
            return response;
        } catch (IOException e) {
            log.error("Failed to read uploaded audio bytes: {}", e.getMessage());
            throw new IllegalArgumentException("Unable to read uploaded audio file", e);
        } catch (RestClientResponseException e) {
            log.error("Speech transcription service returned HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode().is4xxClientError()) {
                throw new IllegalArgumentException("Speech transcription service rejected audio file: " + e.getResponseBodyAsString(), e);
            }
            throw new IllegalStateException("Speech Transcription Service unavailable", e);
        } catch (RestClientException e) {
            log.error("Failed to communicate with speech transcription service: {}", e.getMessage());
            throw new IllegalStateException("Speech Transcription Service unavailable", e);
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