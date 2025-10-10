package com.orchestrator.wiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.orchestrator.wiremock.client.ExternalApiClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for WireMock integration tests.
 *
 * Provides:
 * - WireMock server setup and teardown
 * - Common test utilities
 * - Autowired Spring beans
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseWireMockTest {

    protected WireMockServer wireMockServer;
    protected String baseUrl;

    @Autowired
    protected ExternalApiClient apiClient;

    @BeforeEach
    void setUp() {
        // Configure WireMock server
        wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .dynamicHttpsPort());

        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        baseUrl = "http://localhost:" + wireMockServer.port();

        log.info("WireMock server started on port: {}", wireMockServer.port());
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
            log.info("WireMock server stopped");
        }
    }

    /**
     * Helper method to get the full URL for a given path.
     */
    protected String url(String path) {
        return baseUrl + path;
    }

    /**
     * Helper method to get the HTTPS URL for a given path.
     */
    protected String httpsUrl(String path) {
        return "https://localhost:" + wireMockServer.httpsPort() + path;
    }
}
