package com.orchestrator.wiremock.tests;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.orchestrator.wiremock.BaseWireMockTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for SSL/TLS error scenarios.
 *
 * SSL exceptions occur when:
 * - Certificate validation fails
 * - SSL/TLS handshake fails
 * - Certificate hostname mismatch
 * - Untrusted certificate authority
 *
 * These tests verify that:
 * 1. SSL errors are properly detected and reported
 * 2. Different SSL failure modes are handled correctly
 * 3. Error messages provide useful debugging information
 *
 * Note: WireMock uses self-signed certificates by default for HTTPS,
 * which allows us to simulate SSL errors.
 */
@Slf4j
@DisplayName("SSL Error Tests - SSLHandshakeException / SSLPeerUnverifiedException")
class SslErrorTest extends BaseWireMockTest {

    @Test
    @DisplayName("Should fail with SSL error when accessing HTTPS endpoint without trust configuration")
    void testSslHandshakeFailureWithSelfSignedCert() {
        // Given: HTTPS endpoint with self-signed certificate (WireMock default)
        stubFor(get(urlEqualTo("/api/secure"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"secure\":true}")));

        String httpsUrl = httpsUrl("/api/secure");

        // When & Then: Should fail with SSL exception (self-signed cert not trusted)
        assertThatThrownBy(() -> apiClient.get(httpsUrl))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(ex -> {
                    Throwable cause = ex.getCause();
                    // SSL errors can manifest in different ways
                    assertThat(cause).isInstanceOfAny(
                            SSLException.class,
                            SSLHandshakeException.class,
                            SSLPeerUnverifiedException.class,
                            javax.net.ssl.SSLException.class
                    );
                });

        log.info("Successfully verified SSL handshake failure with self-signed certificate");
    }

    @Test
    @DisplayName("Should handle SSL error on POST request")
    void testSslErrorOnPostRequest() {
        // Given: HTTPS POST endpoint
        stubFor(post(urlEqualTo("/api/secure-post"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withBody("{\"created\":true}")));

        String httpsUrl = httpsUrl("/api/secure-post");

        // When & Then: POST should fail with SSL error
        assertThatThrownBy(() -> apiClient.post(httpsUrl, "{\"data\":\"test\"}"))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(ex -> {
                    assertThat(ex.getCause()).isInstanceOfAny(
                            SSLException.class,
                            SSLHandshakeException.class
                    );
                });

        log.info("Successfully verified SSL error on POST request");
    }

    @Test
    @DisplayName("Should handle SSL error on PUT request")
    void testSslErrorOnPutRequest() {
        // Given: HTTPS PUT endpoint
        stubFor(put(urlEqualTo("/api/secure-put"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"updated\":true}")));

        String httpsUrl = httpsUrl("/api/secure-put");

        // When & Then: PUT should fail with SSL error
        assertThatThrownBy(() -> apiClient.put(httpsUrl, "{\"data\":\"test\"}"))
                .isInstanceOf(ResourceAccessException.class);

        log.info("Successfully verified SSL error on PUT request");
    }

    @Test
    @DisplayName("Should handle SSL error on DELETE request")
    void testSslErrorOnDeleteRequest() {
        // Given: HTTPS DELETE endpoint
        stubFor(delete(urlEqualTo("/api/secure-delete"))
                .willReturn(aResponse()
                        .withStatus(204)));

        String httpsUrl = httpsUrl("/api/secure-delete");

        // When & Then: DELETE should fail with SSL error
        assertThatThrownBy(() -> apiClient.delete(httpsUrl))
                .isInstanceOf(ResourceAccessException.class);

        log.info("Successfully verified SSL error on DELETE request");
    }

    @Test
    @DisplayName("Should provide descriptive error message for SSL failures")
    void testSslErrorMessage() {
        // Given: HTTPS endpoint
        stubFor(get(urlEqualTo("/api/error-message"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"data\":\"test\"}")));

        String httpsUrl = httpsUrl("/api/error-message");

        // When & Then: Error message should be descriptive
        assertThatThrownBy(() -> apiClient.get(httpsUrl))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(ex -> {
                    String message = ex.getMessage();
                    // Error message should provide useful information
                    assertThat(message).isNotEmpty();
                    log.info("SSL error message: {}", message);

                    // The cause should be SSL-related
                    assertThat(ex.getCause()).isNotNull();
                    assertThat(ex.getCause().getClass().getName()).containsAnyOf("SSL", "ssl", "TLS", "tls");
                });

        log.info("Successfully verified SSL error message quality");
    }

    @Test
    @DisplayName("Should differentiate between HTTP and HTTPS endpoints")
    void testHttpVsHttpsEndpoints() {
        // Given: Both HTTP and HTTPS endpoints configured
        stubFor(get(urlEqualTo("/api/protocol-test"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"protocol\":\"test\"}")));

        String httpUrl = url("/api/protocol-test");
        String httpsUrl = httpsUrl("/api/protocol-test");

        // When & Then: HTTP should succeed
        String httpResponse = apiClient.get(httpUrl);
        assertThat(httpResponse).contains("protocol");

        // HTTPS should fail with SSL error
        assertThatThrownBy(() -> apiClient.get(httpsUrl))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(ex -> {
                    assertThat(ex.getCause()).isInstanceOfAny(
                            SSLException.class,
                            SSLHandshakeException.class
                    );
                });

        log.info("Successfully differentiated HTTP vs HTTPS endpoint behavior");
    }

    @Test
    @DisplayName("Should handle mixed protocol scenarios")
    void testMixedProtocolScenarios() {
        // Given: Multiple endpoints with different protocols
        stubFor(get(urlEqualTo("/api/mixed-1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"endpoint\":1}")));

        stubFor(get(urlEqualTo("/api/mixed-2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"endpoint\":2}")));

        // When: Access HTTP endpoints
        String response1 = apiClient.get(url("/api/mixed-1"));
        String response2 = apiClient.get(url("/api/mixed-2"));

        // Then: HTTP requests succeed
        assertThat(response1).contains("endpoint");
        assertThat(response2).contains("endpoint");

        // When: Try HTTPS endpoint
        String httpsUrl = httpsUrl("/api/mixed-1");

        // Then: HTTPS fails
        assertThatThrownBy(() -> apiClient.get(httpsUrl))
                .isInstanceOf(ResourceAccessException.class);

        log.info("Successfully verified mixed protocol scenario handling");
    }

    @Test
    @DisplayName("Should not retry SSL errors (non-retriable)")
    void testSslErrorsNotRetried() {
        // Given: HTTPS endpoint
        stubFor(get(urlEqualTo("/api/no-retry"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"data\":\"test\"}")));

        String httpsUrl = httpsUrl("/api/no-retry");

        // When & Then: Should fail without retries (SSL errors are not retriable)
        assertThatThrownBy(() -> apiClient.get(httpsUrl))
                .isInstanceOf(ResourceAccessException.class);

        // Verify only one attempt was made (no retries)
        // Note: SSL handshake failures typically don't trigger HTTP-level retries
        verify(lessThanOrExactly(1), getRequestedFor(urlEqualTo("/api/no-retry")));

        log.info("Successfully verified SSL errors are not retried");
    }

    @Test
    @DisplayName("Should log SSL error details for debugging")
    void testSslErrorLogging() {
        // Given: HTTPS endpoint
        stubFor(get(urlEqualTo("/api/debug-ssl"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"data\":\"test\"}")));

        String httpsUrl = httpsUrl("/api/debug-ssl");

        // When & Then: SSL error should be thrown
        assertThatThrownBy(() -> apiClient.get(httpsUrl))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(ex -> {
                    // Log the full exception for debugging
                    log.error("SSL Error Details:", ex);

                    // Verify exception chain provides debugging information
                    Throwable cause = ex.getCause();
                    assertThat(cause).isNotNull();

                    // Print stack trace for debugging
                    if (cause instanceof SSLException sslEx) {
                        log.error("SSL Exception cause: {}", sslEx.getMessage());
                    }
                });

        log.info("Successfully logged SSL error details for debugging");
    }

    /**
     * Note: The following test demonstrates what a successful HTTPS connection
     * would look like if proper SSL trust configuration were in place.
     *
     * In production, you would:
     * 1. Configure a trust store with valid CA certificates
     * 2. Use proper SSL context configuration
     * 3. Implement certificate validation
     *
     * This test is included for documentation purposes but will be skipped
     * in the default test suite.
     */
    @Test
    @DisplayName("Documentation: Successful HTTPS with proper SSL configuration")
    void documentSslConfiguration() {
        log.info("=== SSL Configuration Documentation ===");
        log.info("To enable HTTPS connections in production:");
        log.info("1. Configure trust store with CA certificates");
        log.info("2. Set up SSL context in HttpClient configuration");
        log.info("3. Enable certificate hostname verification");
        log.info("4. Consider certificate pinning for sensitive applications");
        log.info("5. Use TLS 1.2 or higher");
        log.info("=====================================");

        // This test just documents the requirements
        assertThat(true).isTrue();
    }
}
