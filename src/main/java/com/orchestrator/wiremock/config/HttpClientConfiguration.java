package com.orchestrator.wiremock.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.ConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HeaderElement;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.message.BasicHeaderElementIterator;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Production-grade Apache HttpClient5 configuration with connection pooling.
 *
 * Features:
 * - Connection pooling with configurable max connections
 * - Stale connection checking
 * - Connection eviction for idle connections
 * - Retry strategy with exponential backoff
 * - Keep-alive strategy
 * - Comprehensive timeout configuration
 * - Metrics integration
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class HttpClientConfiguration {

    private final HttpClientProperties properties;
    private final MeterRegistry meterRegistry;

    /**
     * Creates a pooling connection manager with production-grade settings.
     *
     * Key features:
     * - Connection validation after inactivity
     * - Configurable max connections per route and total
     * - Connection and socket timeout configuration
     */
    @Bean
    public PoolingHttpClientConnectionManager poolingConnectionManager() {
        log.info("Initializing PoolingHttpClientConnectionManager with maxTotal={}, maxPerRoute={}",
                properties.getConnection().getPool().getMaxTotal(),
                properties.getConnection().getPool().getMaxPerRoute());

        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(properties.getConnection().getTimeout().getConnectMs()))
                .setSocketTimeout(Timeout.ofMilliseconds(properties.getConnection().getTimeout().getSocketMs()))
                .setValidateAfterInactivity(TimeValue.ofMilliseconds(
                        properties.getConnection().getPool().getValidateAfterInactivityMs()))
                .build();

        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(properties.getConnection().getPool().getMaxTotal())
                .setMaxConnPerRoute(properties.getConnection().getPool().getMaxPerRoute())
                .setDefaultConnectionConfig(connectionConfig)
                .build();

        // Register metrics for connection pool monitoring
        meterRegistry.gauge("http.client.pool.total.max", connectionManager,
                cm -> (double) properties.getConnection().getPool().getMaxTotal());
        meterRegistry.gauge("http.client.pool.total.available", connectionManager,
                cm -> (double) cm.getTotalStats().getAvailable());

        log.info("Connection pool initialized successfully");
        return connectionManager;
    }

    /**
     * Custom retry strategy with exponential backoff.
     *
     * Retries on:
     * - IOException (connection failures)
     * - Idempotent requests only (GET, HEAD, PUT, DELETE, OPTIONS, TRACE)
     */
    @Bean
    public HttpRequestRetryStrategy retryStrategy() {
        if (!properties.getConnection().getRetry().isEnabled()) {
            log.info("Retry handler disabled");
            return new DefaultHttpRequestRetryStrategy(0, TimeValue.ZERO_MILLISECONDS);
        }

        int retryCount = properties.getConnection().getRetry().getCount();
        long retryInterval = properties.getConnection().getRetry().getIntervalMs();

        log.info("Configuring retry strategy: count={}, interval={}ms", retryCount, retryInterval);

        return new DefaultHttpRequestRetryStrategy(
                retryCount,
                TimeValue.ofMilliseconds(retryInterval)
        ) {
            @Override
            public boolean retryRequest(
                    final org.apache.hc.core5.http.HttpRequest request,
                    final IOException exception,
                    final int execCount,
                    final HttpContext context) {

                boolean shouldRetry = super.retryRequest(request, exception, execCount, context);

                if (shouldRetry) {
                    log.warn("Retrying request (attempt {}/{}): {} - Exception: {}",
                            execCount, retryCount, request.getRequestUri(), exception.getMessage());
                    meterRegistry.counter("http.client.retry",
                            "exception", exception.getClass().getSimpleName()).increment();
                } else {
                    log.error("Not retrying request after {} attempts: {} - Exception: {}",
                            execCount, request.getRequestUri(), exception.getMessage());
                }

                return shouldRetry;
            }
        };
    }

    /**
     * Custom keep-alive strategy.
     *
     * Uses server-provided Keep-Alive header if available,
     * otherwise falls back to configured default.
     */
    @Bean
    public ConnectionKeepAliveStrategy keepAliveStrategy() {
        if (!properties.getConnection().getKeepAlive().isEnabled()) {
            log.info("Keep-alive strategy disabled");
            return (response, context) -> TimeValue.ZERO_MILLISECONDS;
        }

        long defaultKeepAlive = properties.getConnection().getKeepAlive().getDurationMs();
        log.info("Configuring keep-alive strategy: default duration={}ms", defaultKeepAlive);

        return (HttpResponse response, HttpContext context) -> {
            // Honor 'keep-alive' header if present
            BasicHeaderElementIterator it = new BasicHeaderElementIterator(
                    response.headerIterator("Keep-Alive"));

            while (it.hasNext()) {
                HeaderElement element = it.next();
                String param = element.getName();
                String value = element.getValue();

                if (value != null && param.equalsIgnoreCase("timeout")) {
                    try {
                        long serverKeepAlive = Long.parseLong(value) * 1000;
                        log.debug("Using server-provided keep-alive: {}ms", serverKeepAlive);
                        return TimeValue.ofMilliseconds(serverKeepAlive);
                    } catch (NumberFormatException e) {
                        log.warn("Invalid keep-alive timeout value: {}", value);
                    }
                }
            }

            // Fall back to default
            log.debug("Using default keep-alive: {}ms", defaultKeepAlive);
            return TimeValue.ofMilliseconds(defaultKeepAlive);
        };
    }

    /**
     * Closeable HTTP client with all production-grade configurations.
     *
     * Features:
     * - Connection pooling
     * - Retry strategy
     * - Keep-alive strategy
     * - Request timeout configuration
     * - Connection eviction
     */
    @Bean
    public CloseableHttpClient httpClient(
            PoolingHttpClientConnectionManager connectionManager,
            HttpRequestRetryStrategy retryStrategy,
            ConnectionKeepAliveStrategy keepAliveStrategy) {

        log.info("Building CloseableHttpClient with production-grade configuration");

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(
                        properties.getConnection().getTimeout().getConnectionRequestMs()))
                .setResponseTimeout(Timeout.ofMilliseconds(
                        properties.getConnection().getTimeout().getSocketMs()))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setRetryStrategy(retryStrategy)
                .setKeepAliveStrategy(keepAliveStrategy)
                .setDefaultRequestConfig(requestConfig)
                .evictIdleConnections(TimeValue.ofMilliseconds(
                        properties.getConnection().getPool().getEvictIdleConnectionsAfterMs()))
                .build();

        log.info("CloseableHttpClient built successfully");
        return httpClient;
    }

    /**
     * HTTP request factory for Spring's RestClient/RestTemplate.
     */
    @Bean
    public HttpComponentsClientHttpRequestFactory requestFactory(CloseableHttpClient httpClient) {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);

        // Note: Timeouts are already configured in the HttpClient itself
        // These are legacy settings for compatibility
        factory.setConnectTimeout((int) properties.getConnection().getTimeout().getConnectMs());

        log.info("HttpComponentsClientHttpRequestFactory created");
        return factory;
    }
}
