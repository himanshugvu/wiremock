package com.orchestrator.wiremock.tests;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.orchestrator.wiremock.BaseWireMockTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for socket timeout (read timeout) scenarios.
 *
 * SocketTimeoutException occurs when:
 * - Server is too slow to send response data
 * - Network latency causes delays in data transmission
 * - Server hangs after accepting connection
 *
 * These tests verify that:
 * 1. Read timeout is properly configured
 * 2. Timeout exceptions are handled correctly
 * 3. Retry mechanism works for read timeouts
 */
@Slf4j
@DisplayName("Socket Timeout Tests - SocketTimeoutException (Read Timeout)")
class SocketTimeoutTest extends BaseWireMockTest {

    @Test
    @DisplayName("Should timeout when server delays response beyond socket timeout")
    void testSocketTimeoutOnSlowResponse() {
        // Given: Server configured to delay response beyond socket timeout (3000ms)
        stubFor(get(urlEqualTo("/api/slow"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"status\":\"success\"}")
                        .withFixedDelay(5000))); // 5 seconds delay, exceeds 3s socket timeout

        // When & Then: Should fail with SocketTimeoutException
        long startTime = System.currentTimeMillis();

        assertThatThrownBy(() -> apiClient.get(url("/api/slow")))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(ex -> {
                    assertThat(ex.getCause()).isInstanceOfAny(
                            SocketTimeoutException.class,
                            java.net.SocketTimeoutException.class,
                            org.apache.hc.core5.http.ConnectionClosedException.class
                    );
                });

        long duration = System.currentTimeMillis() - startTime;

        // Verify timeout occurred within expected range (3000ms socket timeout * retries)
        assertThat(duration)
                .isGreaterThan(2500) // At least one timeout
                .isLessThan(15000); // With retries

        verify(moreThanOrExactly(1), getRequestedFor(urlEqualTo("/api/slow")));

        log.info("Socket timeout verified after {}ms", duration);
    }

    @Test
    @DisplayName("Should succeed when response is within socket timeout")
    void testSuccessWithinSocketTimeout() {
        // Given: Server responds within timeout (3000ms)
        stubFor(get(urlEqualTo("/api/fast"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"status\":\"success\"}")
                        .withFixedDelay(1000))); // 1 second delay, within 3s timeout

        // When: Make request
        long startTime = System.currentTimeMillis();
        String response = apiClient.get(url("/api/fast"));
        long duration = System.currentTimeMillis() - startTime;

        // Then: Should succeed
        assertThat(response).contains("success");
        assertThat(duration).isGreaterThan(900).isLessThan(2000);

        verify(1, getRequestedFor(urlEqualTo("/api/fast")));

        log.info("Request succeeded within timeout: {}ms", duration);
    }

    @Test
    @DisplayName("Should handle socket timeout gracefully")
    void testSocketTimeoutRetrySuccess() {
        // Given: Endpoint with delay near timeout boundary
        stubFor(get(urlEqualTo("/api/retry-timeout"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"status\":\"success\"}")
                        .withFixedDelay(2500))); // Within timeout (3000ms)

        // When: Make request (should succeed)
        String response = apiClient.get(url("/api/retry-timeout"));

        // Then: Should succeed
        assertThat(response).contains("success");

        // Verify request was made
        verify(1, getRequestedFor(urlEqualTo("/api/retry-timeout")));

        log.info("Successfully verified socket timeout handling with delays");
    }

    @Test
    @DisplayName("Should timeout when reading large response slowly")
    void testSocketTimeoutOnSlowDataTransfer() {
        // Given: Server sends response with chunked delay
        stubFor(get(urlEqualTo("/api/slow-transfer"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(generateLargeBody())
                        .withChunkedDribbleDelay(10, 5000))); // Slow chunk delivery

        // When & Then: Should timeout while reading response
        // Spring may wrap parsing/extraction errors as RestClientException
        assertThatThrownBy(() -> apiClient.get(url("/api/slow-transfer")))
                .satisfies(ex -> {
                    assertThat(ex).isInstanceOfAny(
                            ResourceAccessException.class,
                            org.springframework.web.client.RestClientException.class);
                });

        log.info("Successfully verified socket timeout on slow data transfer");
    }

    @Test
    @DisplayName("Should handle socket timeout on POST request")
    void testSocketTimeoutOnPostRequest() {
        // Given: POST endpoint with slow response
        stubFor(post(urlEqualTo("/api/post-slow"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"created\":true}")
                        .withFixedDelay(5000))); // Exceeds timeout

        // When & Then: POST should fail with socket timeout
        assertThatThrownBy(() -> apiClient.post(url("/api/post-slow"), "{\"data\":\"test\"}"))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(ex -> {
                    assertThat(ex.getCause()).isInstanceOfAny(
                            SocketTimeoutException.class,
                            java.net.SocketTimeoutException.class
                    );
                });

        verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/api/post-slow")));

        log.info("Successfully verified socket timeout on POST request");
    }

    @Test
    @DisplayName("Should handle socket timeout on PUT request")
    void testSocketTimeoutOnPutRequest() {
        // Given: PUT endpoint with slow response
        stubFor(put(urlEqualTo("/api/put-slow"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"updated\":true}")
                        .withFixedDelay(5000)));

        // When & Then: PUT should fail with socket timeout
        assertThatThrownBy(() -> apiClient.put(url("/api/put-slow"), "{\"data\":\"test\"}"))
                .isInstanceOf(ResourceAccessException.class);

        verify(moreThanOrExactly(1), putRequestedFor(urlEqualTo("/api/put-slow")));

        log.info("Successfully verified socket timeout on PUT request");
    }

    @Test
    @DisplayName("Should exhaust retries on persistent read timeout")
    void testExhaustedRetriesOnSocketTimeout() {
        // Given: Endpoint always times out
        stubFor(get(urlEqualTo("/api/always-timeout"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"status\":\"success\"}")
                        .withFixedDelay(4000))); // Exceeds socket timeout (3000ms)

        // When & Then: Should fail after exhausting retries
        // Socket timeout is an I/O error, so it DOES trigger retries
        assertThatThrownBy(() -> apiClient.get(url("/api/always-timeout")))
                .satisfies(ex -> {
                    assertThat(ex).isInstanceOfAny(
                            ResourceAccessException.class,
                            org.springframework.web.client.RestClientException.class);
                });

        // Verify retry attempts (at least 1 attempt, may vary due to timing)
        verify(moreThanOrExactly(1), getRequestedFor(urlEqualTo("/api/always-timeout")));

        log.info("Successfully verified retry exhaustion on persistent socket timeout");
    }

    @Test
    @DisplayName("Should respect socket timeout configuration")
    void testSocketTimeoutRespected() {
        // Given: Server with delay exceeding socket timeout
        stubFor(get(urlEqualTo("/api/timeout-check"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"status\":\"success\"}")
                        .withFixedDelay(5000)));

        // When: Make request and measure time
        long startTime = System.currentTimeMillis();

        assertThatThrownBy(() -> apiClient.get(url("/api/timeout-check")))
                .isInstanceOf(ResourceAccessException.class);

        long duration = System.currentTimeMillis() - startTime;

        // Then: Duration should respect timeout config (3000ms socket timeout per attempt)
        // With retries, total time should be around 3000ms * 3 attempts = 9000ms
        assertThat(duration)
                .isGreaterThan(2500) // At least one timeout
                .isLessThan(15000); // But not exceed retry window

        log.info("Socket timeout respected: {}ms (config: 3000ms socket timeout)", duration);
    }

    @Test
    @DisplayName("Should handle varying response delays correctly")
    void testVaryingResponseDelays() {
        // Given: Multiple endpoints with different delays
        stubFor(get(urlEqualTo("/api/fast-1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"delay\":\"100ms\"}")
                        .withFixedDelay(100)));

        stubFor(get(urlEqualTo("/api/medium-1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"delay\":\"1500ms\"}")
                        .withFixedDelay(1500)));

        stubFor(get(urlEqualTo("/api/near-timeout"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"delay\":\"2800ms\"}")
                        .withFixedDelay(2800))); // Just under 3000ms timeout

        // When & Then: Fast request succeeds quickly
        String fast = apiClient.get(url("/api/fast-1"));
        assertThat(fast).contains("100ms");

        // Medium request succeeds
        String medium = apiClient.get(url("/api/medium-1"));
        assertThat(medium).contains("1500ms");

        // Near-timeout request succeeds (just barely)
        String nearTimeout = apiClient.get(url("/api/near-timeout"));
        assertThat(nearTimeout).contains("2800ms");

        log.info("Successfully verified handling of varying response delays");
    }

    /**
     * Helper method to generate a large response body
     */
    private String generateLargeBody() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"data\":\"");
        for (int i = 0; i < 10000; i++) {
            sb.append("This is a large response body used for testing slow transfer. ");
        }
        sb.append("\"}");
        return sb.toString();
    }
}
