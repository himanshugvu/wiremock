package com.orchestrator.wiremock.tests;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.orchestrator.wiremock.BaseWireMockTest;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for handling stale connections in the connection pool.
 *
 * NoHttpResponseException occurs when:
 * - A connection in the pool becomes stale (server closed it)
 * - Client tries to reuse the stale connection
 * - Server doesn't send any response
 *
 * These tests verify that:
 * 1. Stale connection checking is working
 * 2. Retry mechanism handles stale connections properly
 * 3. Connection pool eviction works correctly
 */
@Slf4j
@DisplayName("Stale Connection Tests - NoHttpResponseException")
class StaleConnectionTest extends BaseWireMockTest {

    @Test
    @DisplayName("Should handle stale connection with EMPTY_RESPONSE fault")
    void testStaleConnectionWithEmptyResponse() {
        // Given: WireMock configured to return empty response (simulating stale connection)
        stubFor(get(urlEqualTo("/api/stale"))
                .willReturn(aResponse()
                        .withFault(Fault.EMPTY_RESPONSE)));

        // When & Then: Request should fail with ResourceAccessException wrapping NoHttpResponseException
        assertThatThrownBy(() -> apiClient.get(url("/api/stale")))
                .isInstanceOf(ResourceAccessException.class)
                .hasMessageContaining("I/O error")
                .hasCauseInstanceOf(NoHttpResponseException.class);

        // Verify the request was attempted
        verify(getRequestedFor(urlEqualTo("/api/stale")));

        log.info("Successfully verified stale connection handling with EMPTY_RESPONSE");
    }

    @Test
    @DisplayName("Should retry on stale connection and succeed on second attempt")
    void testStaleConnectionRetrySuccess() {
        // Given: First request fails with empty response, second succeeds
        stubFor(get(urlEqualTo("/api/retry-stale"))
                .inScenario("Stale Connection Retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withFault(Fault.EMPTY_RESPONSE))
                .willSetStateTo("First Attempt Failed"));

        stubFor(get(urlEqualTo("/api/retry-stale"))
                .inScenario("Stale Connection Retry")
                .whenScenarioStateIs("First Attempt Failed")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success\",\"message\":\"Recovered from stale connection\"}")));

        // When: Make request (should retry and succeed)
        String response = apiClient.get(url("/api/retry-stale"));

        // Then: Response should be successful
        assertThat(response).contains("success");
        assertThat(response).contains("Recovered from stale connection");

        // Verify retry occurred (2 attempts)
        verify(2, getRequestedFor(urlEqualTo("/api/retry-stale")));

        log.info("Successfully verified stale connection retry mechanism");
    }

    @Test
    @DisplayName("Should handle multiple stale connections in sequence")
    void testMultipleStaleConnectionsInSequence() {
        // Given: Configure endpoint that fails multiple times then succeeds
        stubFor(get(urlEqualTo("/api/sequential-stale"))
                .inScenario("Sequential Stale")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withFault(Fault.EMPTY_RESPONSE))
                .willSetStateTo("Attempt 1"));

        stubFor(get(urlEqualTo("/api/sequential-stale"))
                .inScenario("Sequential Stale")
                .whenScenarioStateIs("Attempt 1")
                .willReturn(aResponse()
                        .withFault(Fault.EMPTY_RESPONSE))
                .willSetStateTo("Attempt 2"));

        stubFor(get(urlEqualTo("/api/sequential-stale"))
                .inScenario("Sequential Stale")
                .whenScenarioStateIs("Attempt 2")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"attempt\":2}")));

        // When: Make request (should retry twice and succeed on third attempt)
        String response = apiClient.get(url("/api/sequential-stale"));

        // Then: Should eventually succeed
        assertThat(response).contains("\"attempt\":2");

        // Verify all three attempts were made
        verify(3, getRequestedFor(urlEqualTo("/api/sequential-stale")));

        log.info("Successfully verified handling of multiple sequential stale connections");
    }

    @Test
    @DisplayName("Should handle stale connection after keep-alive timeout")
    void testStaleConnectionAfterKeepAliveTimeout() throws InterruptedException {
        // Given: First request succeeds, establishing connection
        stubFor(get(urlEqualTo("/api/keepalive"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Connection", "keep-alive")
                        .withHeader("Keep-Alive", "timeout=1") // 1 second timeout
                        .withBody("{\"status\":\"initial\"}")));

        // When: Make first request
        String firstResponse = apiClient.get(url("/api/keepalive"));
        assertThat(firstResponse).contains("initial");

        log.info("First request successful, connection established");

        // Wait for keep-alive to expire (simulating stale connection)
        Thread.sleep(2000); // Wait 2 seconds

        // Reconfigure stub to return empty response (simulating stale connection)
        stubFor(get(urlEqualTo("/api/keepalive"))
                .willReturn(aResponse()
                        .withFault(Fault.EMPTY_RESPONSE)));

        // Then: Second request should detect stale connection and fail
        assertThatThrownBy(() -> apiClient.get(url("/api/keepalive")))
                .isInstanceOf(ResourceAccessException.class);

        log.info("Successfully verified stale connection detection after keep-alive timeout");
    }

    @Test
    @DisplayName("Should validate connections after inactivity period")
    void testConnectionValidationAfterInactivity() throws InterruptedException {
        // Given: Configure successful endpoint
        stubFor(get(urlEqualTo("/api/validate"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"validated\":true}")));

        // When: Make first request to establish connection
        String firstResponse = apiClient.get(url("/api/validate"));
        assertThat(firstResponse).contains("validated");

        log.info("Connection established");

        // Wait for validate-after-inactivity period (configured as 1000ms in test profile)
        Thread.sleep(1500);

        // Make second request (should trigger validation)
        String secondResponse = apiClient.get(url("/api/validate"));
        assertThat(secondResponse).contains("validated");

        // Verify both requests succeeded
        verify(2, getRequestedFor(urlEqualTo("/api/validate")));

        log.info("Successfully verified connection validation after inactivity");
    }

    @Test
    @DisplayName("Should handle stale connection on POST request")
    void testStaleConnectionOnPostRequest() {
        // Given: POST endpoint with empty response
        stubFor(post(urlEqualTo("/api/post-stale"))
                .willReturn(aResponse()
                        .withFault(Fault.EMPTY_RESPONSE)));

        // When & Then: POST request should fail
        assertThatThrownBy(() -> apiClient.post(url("/api/post-stale"), "{\"data\":\"test\"}"))
                .isInstanceOf(ResourceAccessException.class)
                .hasCauseInstanceOf(NoHttpResponseException.class);

        verify(postRequestedFor(urlEqualTo("/api/post-stale")));

        log.info("Successfully verified stale connection handling on POST request");
    }

    @Test
    @DisplayName("Should exhaust retries on persistent stale connections")
    void testExhaustedRetriesOnStaleConnection() {
        // Given: Endpoint always returns empty response
        stubFor(get(urlEqualTo("/api/always-stale"))
                .willReturn(aResponse()
                        .withFault(Fault.EMPTY_RESPONSE)));

        // When & Then: Should fail after exhausting retries
        assertThatThrownBy(() -> apiClient.get(url("/api/always-stale")))
                .isInstanceOf(ResourceAccessException.class)
                .hasCauseInstanceOf(NoHttpResponseException.class);

        // Verify retry attempts (initial + 2 retries = 3 total, configured in test profile)
        verify(3, getRequestedFor(urlEqualTo("/api/always-stale")));

        log.info("Successfully verified retry exhaustion on persistent stale connection");
    }
}
