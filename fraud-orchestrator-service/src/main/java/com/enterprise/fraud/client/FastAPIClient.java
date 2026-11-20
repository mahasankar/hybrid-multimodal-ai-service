package com.enterprise.fraud.client;

import com.enterprise.fraud.dto.FraudScoringDto.ClaimRequest;
import com.enterprise.fraud.dto.FraudScoringDto.ScoreResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class FastAPIClient {

    private static final Logger log = LoggerFactory.getLogger(FastAPIClient.class);
    private final RestClient restClient;

    public FastAPIClient(RestClient.Builder restClientBuilder,
                         @Value("${ml.scoring.base-url}") String mlServiceBaseUrl) {
        this.restClient = restClientBuilder
                .baseUrl(mlServiceBaseUrl)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public ScoreResponse scoreClaim(ClaimRequest request) {
        try {
            log.info("Sending expense claim to ML inference service...");
            ScoreResponse response = this.restClient.post()
                    .uri("/score")
                    .body(request)
                    .retrieve()
                    .body(ScoreResponse.class);

            if (response == null) {
                throw new IllegalStateException("ML Scoring Service returned an empty response body");
            }
            return response;
        } catch (RestClientResponseException e) {
            log.error("ML scoring service returned HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode().is4xxClientError()) {
                throw new IllegalArgumentException("ML Scoring Service rejected claim payload: " + e.getResponseBodyAsString(), e);
            }
            throw new IllegalStateException("ML Scoring Service unavailable", e);
        } catch (RestClientException e) {
            log.error("Failed to communicate with FastAPI scoring service: {}", e.getMessage());
            throw new IllegalStateException("ML Scoring Service unavailable", e);
        }
    }
}