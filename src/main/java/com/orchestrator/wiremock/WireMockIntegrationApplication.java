package com.orchestrator.wiremock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main application class for WireMock integration tests.
 *
 * This application demonstrates production-grade REST client configuration
 * with Apache HttpClient5 connection pooling and comprehensive error handling.
 */
@SpringBootApplication
public class WireMockIntegrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(WireMockIntegrationApplication.class, args);
    }
}
