package com.orchestrator.wiremock.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Apache HttpClient5.
 *
 * These properties control connection pooling, timeouts, retry behavior,
 * and other advanced HTTP client features.
 */
@Data
@Component
@ConfigurationProperties(prefix = "http.client")
public class HttpClientProperties {

    private ConnectionPool connection = new ConnectionPool();

    @Data
    public static class ConnectionPool {
        private Pool pool = new Pool();
        private Timeout timeout = new Timeout();
        private Retry retry = new Retry();
        private KeepAlive keepAlive = new KeepAlive();
        private boolean staleConnectionCheck = true;
    }

    @Data
    public static class Pool {
        /**
         * Maximum total connections across all routes
         */
        private int maxTotal = 200;

        /**
         * Maximum connections per route
         */
        private int maxPerRoute = 50;

        /**
         * Validate connections after inactivity (ms)
         */
        private int validateAfterInactivityMs = 2000;

        /**
         * Evict idle connections after this duration (ms)
         */
        private long evictIdleConnectionsAfterMs = 60000;
    }

    @Data
    public static class Timeout {
        /**
         * Connection timeout in milliseconds
         */
        private int connectMs = 3000;

        /**
         * Socket/read timeout in milliseconds
         */
        private int socketMs = 5000;

        /**
         * Connection request timeout (time to get connection from pool) in milliseconds
         */
        private int connectionRequestMs = 1000;
    }

    @Data
    public static class Retry {
        /**
         * Enable retry handler
         */
        private boolean enabled = true;

        /**
         * Number of retry attempts
         */
        private int count = 3;

        /**
         * Interval between retries in milliseconds
         */
        private int intervalMs = 1000;
    }

    @Data
    public static class KeepAlive {
        /**
         * Enable keep-alive strategy
         */
        private boolean enabled = true;

        /**
         * Keep-alive duration in milliseconds
         */
        private long durationMs = 30000;
    }
}
