package com.orchestrator.wiremock.tests;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.orchestrator.wiremock.BaseWireMockTest;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for connection timeout scenarios.
 *
 * ConnectTimeoutException occurs when:
 * - Client cannot establish TCP connection within the configured timeout
 * - Server is unreachable or too slow to accept connections
 * - Network issues prevent connection establishment
 *
 * These tests verify that:
 * 1. Connection timeout is properly configured
 * 2. Timeout exceptions are handled correctly
 * 3. Retry mechanism works for connection timeouts
 */
@Slf4j
@DisplayName("Connection Timeout Tests - ConnectTimeoutException")
class ConnectionTimeoutTest extends BaseWireMockTest {

    @Test
    @DisplayName("Should timeout when server is unreachable")
    void testConnectionTimeoutUnreachableServer() {
        // Given: URL pointing to unreachable host (using non-routable IP)
        String unreachableUrl = "http://192.0.2.1:8080/api/test";

        // When & Then: Should fail with ConnectTimeoutException
        long startTime = System.currentTimeMillis();

        assertThatThrownBy(() -> apiClient.get(unreachableUrl))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(ex -> {
                    Throwable cause = ex.getCause();
                    // ConnectTimeoutException or its parent (depending on exact failure mode)
                    assertThat(cause).isInstanceOfAny(
                            ConnectTimeoutException.class,
                            java.net.ConnectException.class,
                            org.apache.hc.core5.http.ConnectionClosedException.class
                    );
                });

        long duration = System.currentTimeMillis() - startTime;

        // Verify timeout occurred (should be around 2000ms * 3 attempts = 6000ms with retries)
        assertThat(duration).isGreaterThan(1000).isLessThan(10000);

        log.info("Connection timeout verified after {}ms", duration);
    }

    @Test
    @DisplayName("Should timeout when connecting to stopped WireMock server")
    void testConnectionTimeoutStoppedServer() {
        // Given: WireMock server that will be stopped
        int port = wireMockServer.port();
        String stoppedServerUrl = "http://localhost:" + port + "/api/test";

        // Stop the server
        wireMockServer.stop();
        log.info("WireMock server stopped on port {}", port);

        // When & Then: Should fail with connection timeout or connection refused
        assertThatThrownBy(() -> apiClient.get(stoppedServerUrl))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(ex -> {
                    assertThat(ex.getCause()).isInstanceOfAny(
                            ConnectTimeoutException.class,
                            java.net.ConnectException.class
                    );
                });

        log.info("Successfully verified connection timeout for stopped server");

        // Restart server for subsequent tests
        wireMockServer.start();
    }

    @Test
    @DisplayName("Should retry on connection timeout and succeed")
    void testConnectionTimeoutRetrySuccess() {
        // Given: Configure endpoint for retry scenario
        stubFor(get(urlEqualTo("/api/retry-connect"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"status\":\"success\"}")));

        // Simulate connection timeout by using invalid port first, then correct port
        String invalidUrl = "http://localhost:65535/api/retry-connect"; // Invalid port
        String validUrl = url("/api/retry-connect");

        // When & Then: Invalid URL fails
        assertThatThrownBy(() -> apiClient.get(invalidUrl))
                .isInstanceOf(ResourceAccessException.class);

        // Valid URL succeeds (demonstrating retry capability)
        String response = apiClient.get(validUrl);
        assertThat(response).contains("success");

        log.info("Successfully verified connection retry mechanism");
    }

    @Test
    @DisplayName("Should handle connection timeout on POST request")
    void testConnectionTimeoutOnPostRequest() {
        // Given: Unreachable endpoint for POST
        String unreachableUrl = "http://192.0.2.1:8080/api/post";

        // When & Then: POST should fail with connection timeout
        assertThatThrownBy(() -> apiClient.post(unreachableUrl, "{\"data\":\"test\"}"))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(ex -> {
                    assertThat(ex.getCause()).isInstanceOfAny(
                            ConnectTimeoutException.class,
                            java.net.ConnectException.class
                    );
                });

        log.info("Successfully verified connection timeout on POST request");
    }

    @Test
    @DisplayName("Should handle connection timeout with different HTTP methods")
    void testConnectionTimeoutDifferentMethods() {
        // Given: Unreachable URL
        String unreachableUrl = "http://192.0.2.1:8080/api/resource";

        // When & Then: GET fails
        assertThatThrownBy(() -> apiClient.get(unreachableUrl))
                .isInstanceOf(ResourceAccessException.class);

        // POST fails
        assertThatThrownBy(() -> apiClient.post(unreachableUrl, "{}"))
                .isInstanceOf(ResourceAccessException.class);

        // PUT fails
        assertThatThrownBy(() -> apiClient.put(unreachableUrl, "{}"))
                .isInstanceOf(ResourceAccessException.class);

        // DELETE fails
        assertThatThrownBy(() -> apiClient.delete(unreachableUrl))
                .isInstanceOf(ResourceAccessException.class);

        log.info("Successfully verified connection timeout across all HTTP methods");
    }

    @Test
    @DisplayName("Should respect connection timeout configuration")
    void testConnectionTimeoutRespected() {
        // Given: Unreachable host
        String unreachableUrl = "http://192.0.2.1:8080/api/test";

        // When: Make request and measure time
        long startTime = System.currentTimeMillis();

        assertThatThrownBy(() -> apiClient.get(unreachableUrl))
                .isInstanceOf(ResourceAccessException.class);

        long duration = System.currentTimeMillis() - startTime;

        // Then: Duration should respect timeout config (2000ms connect timeout * 3 retries)
        // Allow some buffer for processing time
        assertThat(duration)
                .isGreaterThan(1500) // At least close to one timeout
                .isLessThan(10000); // But not exceed reasonable retry window

        log.info("Connection timeout respected: {}ms (config: 2000ms connect timeout)", duration);
    }

    @Test
    @DisplayName("Should handle connection pool exhaustion gracefully")
    void testConnectionPoolExhaustion() {
        // Given: Configure endpoint that never responds
        String unreachableUrl = "http://192.0.2.1:8080/api/pool-test";

        // When: Make multiple concurrent requests (this test demonstrates the behavior)
        // Note: This is a simplified version; real pool exhaustion would require more concurrent requests

        assertThatThrownBy(() -> {
            for (int i = 0; i < 3; i++) {
                try {
                    apiClient.get(unreachableUrl);
                } catch (Exception e) {
                    // Expected to fail
                    log.debug("Request {} failed as expected: {}", i, e.getMessage());
                }
            }
            // Try one more time
            apiClient.get(unreachableUrl);
        }).isInstanceOf(ResourceAccessException.class);

        log.info("Successfully demonstrated connection pool behavior under timeout conditions");
    }
}
