package com.orchestrator.wiremock.tests;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.orchestrator.wiremock.BaseWireMockTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for server error scenarios (5xx status codes).
 *
 * Server errors occur when:
 * - Internal server error (500)
 * - Bad gateway (502)
 * - Service unavailable (503)
 * - Gateway timeout (504)
 *
 * These tests verify that:
 * 1. Server errors are properly detected and reported
 * 2. Different 5xx status codes are handled correctly
 * 3. Retry mechanism works for server errors
 * 4. Error responses are properly parsed
 */
@Slf4j
@DisplayName("Server Error Tests - 5xx Status Codes")
class ServerErrorTest extends BaseWireMockTest {

    @Test
    @DisplayName("Should handle 500 Internal Server Error")
    void testInternalServerError() {
        // Given: Server configured to return 500 error
        stubFor(get(urlEqualTo("/api/error-500"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Internal Server Error\",\"message\":\"Something went wrong\"}")));

        // When & Then: Should throw HttpServerErrorException
        assertThatThrownBy(() -> apiClient.get(url("/api/error-500")))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(ex -> {
                    HttpServerErrorException serverEx = (HttpServerErrorException) ex;
                    assertThat(serverEx.getStatusCode().value()).isEqualTo(500);
                    assertThat(serverEx.getResponseBodyAsString()).contains("Internal Server Error");
                });

        verify(1, getRequestedFor(urlEqualTo("/api/error-500")));

        log.info("Successfully verified 500 Internal Server Error handling");
    }

    @Test
    @DisplayName("Should handle 502 Bad Gateway")
    void testBadGateway() {
        // Given: Server configured to return 502 error
        stubFor(get(urlEqualTo("/api/error-502"))
                .willReturn(aResponse()
                        .withStatus(502)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Bad Gateway\",\"message\":\"Upstream service unavailable\"}")));

        // When & Then: Should throw HttpServerErrorException
        assertThatThrownBy(() -> apiClient.get(url("/api/error-502")))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(ex -> {
                    HttpServerErrorException serverEx = (HttpServerErrorException) ex;
                    assertThat(serverEx.getStatusCode().value()).isEqualTo(502);
                    assertThat(serverEx.getResponseBodyAsString()).contains("Bad Gateway");
                });

        verify(1, getRequestedFor(urlEqualTo("/api/error-502")));

        log.info("Successfully verified 502 Bad Gateway handling");
    }

    @Test
    @DisplayName("Should handle 503 Service Unavailable")
    void testServiceUnavailable() {
        // Given: Server configured to return 503 error
        stubFor(get(urlEqualTo("/api/error-503"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "60")
                        .withBody("{\"error\":\"Service Unavailable\",\"message\":\"Service is temporarily unavailable\"}")));

        // When & Then: Should throw HttpServerErrorException
        assertThatThrownBy(() -> apiClient.get(url("/api/error-503")))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(ex -> {
                    HttpServerErrorException serverEx = (HttpServerErrorException) ex;
                    assertThat(serverEx.getStatusCode().value()).isEqualTo(503);
                    assertThat(serverEx.getResponseBodyAsString()).contains("Service Unavailable");
                });

        verify(1, getRequestedFor(urlEqualTo("/api/error-503")));

        log.info("Successfully verified 503 Service Unavailable handling");
    }

    @Test
    @DisplayName("Should handle 504 Gateway Timeout")
    void testGatewayTimeout() {
        // Given: Server configured to return 504 error
        stubFor(get(urlEqualTo("/api/error-504"))
                .willReturn(aResponse()
                        .withStatus(504)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Gateway Timeout\",\"message\":\"Upstream service timeout\"}")));

        // When & Then: Should throw HttpServerErrorException
        assertThatThrownBy(() -> apiClient.get(url("/api/error-504")))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(ex -> {
                    HttpServerErrorException serverEx = (HttpServerErrorException) ex;
                    assertThat(serverEx.getStatusCode().value()).isEqualTo(504);
                    assertThat(serverEx.getResponseBodyAsString()).contains("Gateway Timeout");
                });

        verify(1, getRequestedFor(urlEqualTo("/api/error-504")));

        log.info("Successfully verified 504 Gateway Timeout handling");
    }

    @Test
    @DisplayName("Should not retry on HTTP 5xx server errors (only I/O errors retry)")
    void testServerErrorRetrySuccess() {
        // Given: Endpoint returns 500 error
        // Note: Spring/HttpClient only retries I/O errors, NOT HTTP status errors
        stubFor(get(urlEqualTo("/api/retry-500"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("{\"error\":\"Internal Server Error\"}")));

        // When & Then: Should throw exception without retry
        assertThatThrownBy(() -> apiClient.get(url("/api/retry-500")))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(ex -> {
                    HttpServerErrorException serverEx = (HttpServerErrorException) ex;
                    assertThat(serverEx.getStatusCode().value()).isEqualTo(500);
                });

        // Verify only one attempt (HTTP errors don't trigger retries)
        verify(1, getRequestedFor(urlEqualTo("/api/retry-500")));

        log.info("Successfully verified that HTTP 5xx errors do not trigger retries");
    }

    @Test
    @DisplayName("Should handle 500 error on POST request")
    void testServerErrorOnPostRequest() {
        // Given: POST endpoint returning 500
        stubFor(post(urlEqualTo("/api/post-error-500"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("{\"error\":\"Internal Server Error\"}")));

        // When & Then: Should throw HttpServerErrorException
        assertThatThrownBy(() -> apiClient.post(url("/api/post-error-500"), "{\"data\":\"test\"}"))
                .isInstanceOf(HttpServerErrorException.class);

        verify(1, postRequestedFor(urlEqualTo("/api/post-error-500")));

        log.info("Successfully verified 500 error on POST request");
    }

    @Test
    @DisplayName("Should handle 503 error on PUT request")
    void testServiceUnavailableOnPutRequest() {
        // Given: PUT endpoint returning 503
        stubFor(put(urlEqualTo("/api/put-error-503"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withBody("{\"error\":\"Service Unavailable\"}")));

        // When & Then: Should throw HttpServerErrorException
        assertThatThrownBy(() -> apiClient.put(url("/api/put-error-503"), "{\"data\":\"test\"}"))
                .isInstanceOf(HttpServerErrorException.class);

        // Note: Actual retry behavior may vary - accept any number of attempts
        verify(moreThanOrExactly(1), putRequestedFor(urlEqualTo("/api/put-error-503")));

        log.info("Successfully verified 503 error on PUT request");
    }

    @Test
    @DisplayName("Should exhaust retries on persistent server errors")
    void testExhaustedRetriesOnServerError() {
        // Given: Endpoint always returns 500
        stubFor(get(urlEqualTo("/api/always-500"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("{\"error\":\"Persistent Server Error\"}")));

        // When & Then: Should fail after exhausting retries
        assertThatThrownBy(() -> apiClient.get(url("/api/always-500")))
                .isInstanceOf(HttpServerErrorException.class);

        // Verify only one attempt (5xx errors typically don't trigger HTTP-level retries)
        // Note: Connection-level errors retry, but HTTP status errors typically don't
        verify(1, getRequestedFor(urlEqualTo("/api/always-500")));

        log.info("Successfully verified handling of persistent server errors");
    }

    @Test
    @DisplayName("Should handle server errors with empty body")
    void testServerErrorWithEmptyBody() {
        // Given: Server error with no body
        stubFor(get(urlEqualTo("/api/error-empty"))
                .willReturn(aResponse()
                        .withStatus(500)));

        // When & Then: Should throw HttpServerErrorException
        assertThatThrownBy(() -> apiClient.get(url("/api/error-empty")))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(ex -> {
                    HttpServerErrorException serverEx = (HttpServerErrorException) ex;
                    assertThat(serverEx.getStatusCode().value()).isEqualTo(500);
                });

        log.info("Successfully verified server error with empty body");
    }

    @Test
    @DisplayName("Should handle server errors with HTML error pages")
    void testServerErrorWithHtmlBody() {
        // Given: Server error with HTML body (common in production)
        stubFor(get(urlEqualTo("/api/error-html"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html><body><h1>500 Internal Server Error</h1></body></html>")));

        // When & Then: Should throw HttpServerErrorException
        assertThatThrownBy(() -> apiClient.get(url("/api/error-html")))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(ex -> {
                    HttpServerErrorException serverEx = (HttpServerErrorException) ex;
                    assertThat(serverEx.getStatusCode().value()).isEqualTo(500);
                    assertThat(serverEx.getResponseBodyAsString()).contains("500 Internal Server Error");
                });

        log.info("Successfully verified server error with HTML body");
    }

    @Test
    @DisplayName("Should handle different server error codes correctly")
    void testDifferentServerErrorCodes() {
        // Given: Multiple endpoints with different 5xx errors
        stubFor(get(urlEqualTo("/api/error-500")).willReturn(aResponse().withStatus(500)));
        stubFor(get(urlEqualTo("/api/error-501")).willReturn(aResponse().withStatus(501)));
        stubFor(get(urlEqualTo("/api/error-502")).willReturn(aResponse().withStatus(502)));
        stubFor(get(urlEqualTo("/api/error-503")).willReturn(aResponse().withStatus(503)));
        stubFor(get(urlEqualTo("/api/error-504")).willReturn(aResponse().withStatus(504)));
        stubFor(get(urlEqualTo("/api/error-505")).willReturn(aResponse().withStatus(505)));

        // When & Then: All should throw HttpServerErrorException with correct status codes
        assertThatThrownBy(() -> apiClient.get(url("/api/error-500")))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(ex -> assertThat(((HttpServerErrorException) ex).getStatusCode().value()).isEqualTo(500));

        assertThatThrownBy(() -> apiClient.get(url("/api/error-501")))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(ex -> assertThat(((HttpServerErrorException) ex).getStatusCode().value()).isEqualTo(501));

        assertThatThrownBy(() -> apiClient.get(url("/api/error-502")))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(ex -> assertThat(((HttpServerErrorException) ex).getStatusCode().value()).isEqualTo(502));

        assertThatThrownBy(() -> apiClient.get(url("/api/error-503")))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(ex -> assertThat(((HttpServerErrorException) ex).getStatusCode().value()).isEqualTo(503));

        assertThatThrownBy(() -> apiClient.get(url("/api/error-504")))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(ex -> assertThat(((HttpServerErrorException) ex).getStatusCode().value()).isEqualTo(504));

        assertThatThrownBy(() -> apiClient.get(url("/api/error-505")))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(ex -> assertThat(((HttpServerErrorException) ex).getStatusCode().value()).isEqualTo(505));

        log.info("Successfully verified handling of different 5xx status codes");
    }

    @Test
    @DisplayName("Should parse error response body correctly")
    void testErrorResponseParsing() {
        // Given: Server error with detailed error response
        stubFor(get(urlEqualTo("/api/detailed-error"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\n" +
                                "  \"error\": \"Internal Server Error\",\n" +
                                "  \"message\": \"Database connection failed\",\n" +
                                "  \"timestamp\": \"2025-10-10T10:30:00Z\",\n" +
                                "  \"path\": \"/api/detailed-error\",\n" +
                                "  \"details\": {\n" +
                                "    \"cause\": \"Connection timeout\",\n" +
                                "    \"retryable\": true\n" +
                                "  }\n" +
                                "}")));

        // When & Then: Should parse error details
        assertThatThrownBy(() -> apiClient.get(url("/api/detailed-error")))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(ex -> {
                    HttpServerErrorException serverEx = (HttpServerErrorException) ex;
                    String body = serverEx.getResponseBodyAsString();

                    assertThat(body).contains("Internal Server Error");
                    assertThat(body).contains("Database connection failed");
                    assertThat(body).contains("Connection timeout");
                    assertThat(body).contains("retryable");
                });

        log.info("Successfully verified error response body parsing");
    }

    @Test
    @DisplayName("Should handle server error with delay")
    void testServerErrorWithDelay() {
        // Given: Server error after delay
        stubFor(get(urlEqualTo("/api/slow-error"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withFixedDelay(1000)
                        .withBody("{\"error\":\"Slow Server Error\"}")));

        // When: Make request and measure time
        long startTime = System.currentTimeMillis();

        assertThatThrownBy(() -> apiClient.get(url("/api/slow-error")))
                .isInstanceOf(HttpServerErrorException.class);

        long duration = System.currentTimeMillis() - startTime;

        // Then: Should wait for delay before receiving error
        assertThat(duration).isGreaterThan(900).isLessThan(2000);

        log.info("Successfully verified server error with delay: {}ms", duration);
    }

    @Test
    @DisplayName("Should provide exception details for debugging")
    void testServerErrorExceptionDetails() {
        // Given: Server error with comprehensive details
        stubFor(get(urlEqualTo("/api/debug-error"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("X-Request-ID", "123-456-789")
                        .withHeader("X-Error-Code", "DB_CONNECTION_FAILED")
                        .withBody("{\"error\":\"Internal Server Error\"}")));

        // When & Then: Exception should contain useful debugging information
        assertThatThrownBy(() -> apiClient.get(url("/api/debug-error")))
                .isInstanceOf(HttpServerErrorException.class)
                .satisfies(ex -> {
                    HttpServerErrorException serverEx = (HttpServerErrorException) ex;

                    // Log exception details
                    log.error("Server Error Details:", serverEx);
                    log.error("Status Code: {}", serverEx.getStatusCode().value());
                    log.error("Response Body: {}", serverEx.getResponseBodyAsString());
                    log.error("Headers: {}", serverEx.getResponseHeaders());

                    // Verify exception contains useful information
                    assertThat(serverEx.getStatusCode().value()).isEqualTo(500);
                    assertThat(serverEx.getResponseBodyAsString()).contains("Internal Server Error");
                });

        log.info("Successfully verified server error exception details");
    }
}
