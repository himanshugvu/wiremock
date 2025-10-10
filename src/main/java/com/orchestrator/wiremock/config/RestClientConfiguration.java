package com.orchestrator.wiremock.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configuration for Spring Boot 3.5 RestClient with Apache HttpClient5.
 *
 * RestClient is the modern replacement for RestTemplate in Spring 6+.
 * It provides a fluent API and better integration with modern Java features.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RestClientConfiguration {

    private final ClientHttpRequestFactory requestFactory;
    private final MeterRegistry meterRegistry;

    /**
     * Creates a RestClient bean configured with Apache HttpClient5 request factory.
     *
     * This RestClient will use the connection pool and all configurations
     * defined in HttpClientConfiguration.
     */
    @Bean
    public RestClient restClient() {
        log.info("Creating RestClient with Apache HttpClient5 request factory");

        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    // Log outgoing requests
                    log.debug("Outgoing request: {} {}", request.getMethod(), request.getURI());

                    // Track request metrics
                    long startTime = System.currentTimeMillis();

                    try {
                        var response = execution.execute(request, body);
                        long duration = System.currentTimeMillis() - startTime;

                        // Track successful requests
                        meterRegistry.timer("http.client.requests",
                                "method", request.getMethod().name(),
                                "status", String.valueOf(response.getStatusCode().value()),
                                "outcome", "SUCCESS"
                        ).record(duration, java.util.concurrent.TimeUnit.MILLISECONDS);

                        log.debug("Response received: {} {} - Status: {} - Duration: {}ms",
                                request.getMethod(), request.getURI(),
                                response.getStatusCode(), duration);

                        return response;
                    } catch (Exception e) {
                        long duration = System.currentTimeMillis() - startTime;

                        // Track failed requests
                        meterRegistry.timer("http.client.requests",
                                "method", request.getMethod().name(),
                                "status", "UNKNOWN",
                                "outcome", "ERROR",
                                "exception", e.getClass().getSimpleName()
                        ).record(duration, java.util.concurrent.TimeUnit.MILLISECONDS);

                        meterRegistry.counter("http.client.errors",
                                "exception", e.getClass().getSimpleName()
                        ).increment();

                        log.error("Request failed: {} {} - Exception: {} - Duration: {}ms",
                                request.getMethod(), request.getURI(),
                                e.getMessage(), duration);

                        throw e;
                    }
                })
                .build();
    }
}
