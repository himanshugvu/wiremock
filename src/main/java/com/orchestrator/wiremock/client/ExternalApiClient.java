package com.orchestrator.wiremock.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Sample REST client service for external API calls.
 *
 * This service demonstrates the usage of Spring's RestClient
 * with Apache HttpClient5 connection pooling.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalApiClient {

    private final RestClient restClient;

    /**
     * Makes a GET request to the specified URL.
     *
     * @param url The target URL
     * @return Response body as String
     */
    public String get(String url) {
        log.info("Making GET request to: {}", url);

        return restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);
    }

    /**
     * Makes a POST request to the specified URL with JSON body.
     *
     * @param url     The target URL
     * @param request Request body
     * @return Response body as String
     */
    public String post(String url, Object request) {
        log.info("Making POST request to: {}", url);

        return restClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .body(request)
                .retrieve()
                .body(String.class);
    }

    /**
     * Makes a PUT request to the specified URL with JSON body.
     *
     * @param url     The target URL
     * @param request Request body
     * @return Response body as String
     */
    public String put(String url, Object request) {
        log.info("Making PUT request to: {}", url);

        return restClient.put()
                .uri(url)
                .header("Content-Type", "application/json")
                .body(request)
                .retrieve()
                .body(String.class);
    }

    /**
     * Makes a DELETE request to the specified URL.
     *
     * @param url The target URL
     */
    public void delete(String url) {
        log.info("Making DELETE request to: {}", url);

        restClient.delete()
                .uri(url)
                .retrieve()
                .toBodilessEntity();
    }
}
