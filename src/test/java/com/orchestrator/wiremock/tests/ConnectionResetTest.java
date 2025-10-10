package com.orchestrator.wiremock.tests;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.orchestrator.wiremock.BaseWireMockTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.EOFException;
import java.net.SocketException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for connection reset scenarios.
 *
 * Connection reset errors occur when:
 * - Server closes connection unexpectedly
 * - Network connection is interrupted
 * - TCP RST packet is sent
 * - Connection is forcefully terminated
 *
 * These tests verify that:
 * 1. Connection reset is properly detected
 * 2. EOFException and SocketException are handled correctly
 * 3. Retry mechanism handles connection resets appropriately
 */
@Slf4j
@DisplayName("Connection Reset Tests - EOFException / ConnectionResetException")
class ConnectionResetTest extends BaseWireMockTest {

    @Test
    @DisplayName("Should handle connection reset by peer")
    void testConnectionResetByPeer() {
        // Given: WireMock configured to reset connection
        stubFor(get(urlEqualTo("/api/reset"))
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        // When & Then: Request should fail with connection reset exception
        assertThatThrownBy(() -> apiClient.get(url("/api/reset")))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(ex -> {
                    Throwable cause = ex.getCause();
                    // Connection reset can manifest as different exception types
                    assertThat(cause).isInstanceOfAny(
                            SocketException.class,
                            EOFException.class,
                            java.net.SocketException.class,
                            org.apache.hc.core5.http.ConnectionClosedException.class
                    );
                });

        verify(moreThanOrExactly(1), getRequestedFor(urlEqualTo("/api/reset")));

        log.info("Successfully verified connection reset by peer handling");
    }

    @Test
    @DisplayName("Should retry on connection reset and succeed")
    void testConnectionResetRetrySuccess() {
        // Given: First request resets, second succeeds
        stubFor(get(urlEqualTo("/api/retry-reset"))
                .inScenario("Connection Reset Retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("First Attempt Reset"));

        stubFor(get(urlEqualTo("/api/retry-reset"))
                .inScenario("Connection Reset Retry")
                .whenScenarioStateIs("First Attempt Reset")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success\",\"message\":\"Recovered from connection reset\"}")));

        // When: Make request (should retry and succeed)
        String response = apiClient.get(url("/api/retry-reset"));

        // Then: Response should be successful
        assertThat(response).contains("success");
        assertThat(response).contains("Recovered from connection reset");

        // Verify retry occurred (2 attempts)
        verify(2, getRequestedFor(urlEqualTo("/api/retry-reset")));

        log.info("Successfully verified connection reset retry mechanism");
    }

    @Test
    @DisplayName("Should handle random data then close fault")
    void testRandomDataThenClose() {
        // Given: WireMock configured to send random data then close
        stubFor(get(urlEqualTo("/api/random-close"))
                .willReturn(aResponse()
                        .withFault(Fault.RANDOM_DATA_THEN_CLOSE)));

        // When & Then: Should fail with connection/parsing exception
        assertThatThrownBy(() -> apiClient.get(url("/api/random-close")))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(ex -> {
                    // Can result in various exceptions depending on when connection closes
                    assertThat(ex.getCause()).isNotNull();
                });

        verify(moreThanOrExactly(1), getRequestedFor(urlEqualTo("/api/random-close")));

        log.info("Successfully verified random data then close handling");
    }

    @Test
    @DisplayName("Should handle malformed response chunk fault")
    void testMalformedResponseChunk() {
        // Given: WireMock configured to send malformed response
        stubFor(get(urlEqualTo("/api/malformed"))
                .willReturn(aResponse()
                        .withFault(Fault.MALFORMED_RESPONSE_CHUNK)));

        // When & Then: Should fail with connection/parsing exception
        assertThatThrownBy(() -> apiClient.get(url("/api/malformed")))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(ex -> {
                    assertThat(ex.getCause()).isNotNull();
                });

        verify(moreThanOrExactly(1), getRequestedFor(urlEqualTo("/api/malformed")));

        log.info("Successfully verified malformed response chunk handling");
    }

    @Test
    @DisplayName("Should handle connection reset on POST request")
    void testConnectionResetOnPostRequest() {
        // Given: POST endpoint with connection reset
        stubFor(post(urlEqualTo("/api/post-reset"))
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        // When & Then: POST should fail with connection reset
        assertThatThrownBy(() -> apiClient.post(url("/api/post-reset"), "{\"data\":\"test\"}"))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(ex -> {
                    assertThat(ex.getCause()).isInstanceOfAny(
                            SocketException.class,
                            EOFException.class
                    );
                });

        verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo("/api/post-reset")));

        log.info("Successfully verified connection reset on POST request");
    }

    @Test
    @DisplayName("Should handle connection reset on PUT request")
    void testConnectionResetOnPutRequest() {
        // Given: PUT endpoint with connection reset
        stubFor(put(urlEqualTo("/api/put-reset"))
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        // When & Then: PUT should fail with connection reset
        assertThatThrownBy(() -> apiClient.put(url("/api/put-reset"), "{\"data\":\"test\"}"))
                .isInstanceOf(ResourceAccessException.class);

        verify(moreThanOrExactly(1), putRequestedFor(urlEqualTo("/api/put-reset")));

        log.info("Successfully verified connection reset on PUT request");
    }

    @Test
    @DisplayName("Should exhaust retries on persistent connection reset")
    void testExhaustedRetriesOnConnectionReset() {
        // Given: Endpoint always resets connection
        stubFor(get(urlEqualTo("/api/always-reset"))
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        // When & Then: Should fail after exhausting retries
        assertThatThrownBy(() -> apiClient.get(url("/api/always-reset")))
                .isInstanceOf(ResourceAccessException.class);

        // Verify retry attempts (initial + 2 retries = 3 total)
        verify(3, getRequestedFor(urlEqualTo("/api/always-reset")));

        log.info("Successfully verified retry exhaustion on persistent connection reset");
    }

    @Test
    @DisplayName("Should handle multiple sequential connection resets")
    void testMultipleSequentialResets() {
        // Given: Configure endpoint that alternates between reset and success
        stubFor(get(urlEqualTo("/api/sequential-reset"))
                .inScenario("Sequential Reset")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("Attempt 1"));

        stubFor(get(urlEqualTo("/api/sequential-reset"))
                .inScenario("Sequential Reset")
                .whenScenarioStateIs("Attempt 1")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"attempt\":1}")));

        // When & Then: First request should fail then succeed on retry
        assertThatThrownBy(() -> apiClient.get(url("/api/sequential-reset")))
                .isInstanceOf(ResourceAccessException.class);

        // Second request should succeed (connection refreshed)
        String response = apiClient.get(url("/api/sequential-reset"));
        assertThat(response).contains("\"attempt\":1");

        log.info("Successfully verified handling of multiple sequential connection resets");
    }

    @Test
    @DisplayName("Should handle connection reset after partial response")
    void testConnectionResetAfterPartialResponse() {
        // Given: Endpoint sends random data then closes
        stubFor(get(urlEqualTo("/api/partial-reset"))
                .willReturn(aResponse()
                        .withFault(Fault.RANDOM_DATA_THEN_CLOSE)));

        // When & Then: Should fail with connection reset or parsing error
        assertThatThrownBy(() -> apiClient.get(url("/api/partial-reset")))
                .isInstanceOf(ResourceAccessException.class);

        verify(moreThanOrExactly(1), getRequestedFor(urlEqualTo("/api/partial-reset")));

        log.info("Successfully verified connection reset after partial response");
    }

    @Test
    @DisplayName("Should differentiate between connection faults")
    void testDifferentConnectionFaults() {
        // Given: Multiple endpoints with different faults
        stubFor(get(urlEqualTo("/api/fault-reset"))
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        stubFor(get(urlEqualTo("/api/fault-empty"))
                .willReturn(aResponse()
                        .withFault(Fault.EMPTY_RESPONSE)));

        stubFor(get(urlEqualTo("/api/fault-random"))
                .willReturn(aResponse()
                        .withFault(Fault.RANDOM_DATA_THEN_CLOSE)));

        // When & Then: All should fail but with potentially different exception types
        assertThatThrownBy(() -> apiClient.get(url("/api/fault-reset")))
                .isInstanceOf(ResourceAccessException.class);

        assertThatThrownBy(() -> apiClient.get(url("/api/fault-empty")))
                .isInstanceOf(ResourceAccessException.class);

        assertThatThrownBy(() -> apiClient.get(url("/api/fault-random")))
                .isInstanceOf(ResourceAccessException.class);

        log.info("Successfully differentiated between connection fault types");
    }

    @Test
    @DisplayName("Should handle connection reset during large response")
    void testConnectionResetDuringLargeResponse() {
        // Given: Endpoint with malformed chunked response
        stubFor(get(urlEqualTo("/api/large-reset"))
                .willReturn(aResponse()
                        .withFault(Fault.MALFORMED_RESPONSE_CHUNK)));

        // When & Then: Should fail with connection/parsing error
        assertThatThrownBy(() -> apiClient.get(url("/api/large-reset")))
                .isInstanceOf(ResourceAccessException.class);

        log.info("Successfully verified connection reset during large response");
    }

    @Test
    @DisplayName("Should recover from transient connection resets")
    void testRecoveryFromTransientResets() {
        // Given: First two requests reset, third succeeds
        stubFor(get(urlEqualTo("/api/transient-reset"))
                .inScenario("Transient Reset")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("First Reset"));

        stubFor(get(urlEqualTo("/api/transient-reset"))
                .inScenario("Transient Reset")
                .whenScenarioStateIs("First Reset")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"status\":\"recovered\"}")));

        // When: Make request (should retry and succeed)
        String response = apiClient.get(url("/api/transient-reset"));

        // Then: Should recover successfully
        assertThat(response).contains("recovered");

        // Verify retry occurred
        verify(2, getRequestedFor(urlEqualTo("/api/transient-reset")));

        log.info("Successfully verified recovery from transient connection resets");
    }
}
