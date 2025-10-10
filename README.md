# WireMock Integration Tests - Apache HttpClient5 Connection Pooling

Production-grade integration test suite for Spring Boot 3.5 (Java 21) REST client with Apache HttpClient5 connection pooling. This project demonstrates comprehensive testing of all major connection-related exceptions using WireMock.

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Test Coverage](#test-coverage)
- [Running Tests](#running-tests)
- [Key Components](#key-components)
- [Exception Handling](#exception-handling)
- [Best Practices](#best-practices)
- [Metrics and Monitoring](#metrics-and-monitoring)
- [Troubleshooting](#troubleshooting)

## Overview

This project provides a complete reference implementation for testing REST client resilience and connection management in Spring Boot applications. It covers all major connection failure scenarios that can occur in production environments.

## Features

### Production-Grade Configuration
- ✅ Apache HttpClient5 with advanced connection pooling
- ✅ Configurable connection limits (max total, max per route)
- ✅ Stale connection checking and validation
- ✅ Automatic idle connection eviction
- ✅ Keep-alive strategy with server negotiation
- ✅ Retry mechanism with exponential backoff
- ✅ Comprehensive timeout configuration (connect, socket, connection request)

### Comprehensive Test Coverage
- ✅ **Stale Connection Tests** - NoHttpResponseException handling
- ✅ **Connection Timeout Tests** - ConnectTimeoutException scenarios
- ✅ **Socket Timeout Tests** - SocketTimeoutException (read timeout)
- ✅ **SSL Error Tests** - SSLHandshakeException, SSLPeerUnverifiedException
- ✅ **Connection Reset Tests** - EOFException, ConnectionResetException
- ✅ **Server Error Tests** - 500, 502, 503, 504 status codes

### Observability
- ✅ Micrometer metrics integration
- ✅ Prometheus endpoint for monitoring
- ✅ Structured logging with request/response tracking
- ✅ Connection pool metrics
- ✅ Retry attempt tracking

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     RestClient Layer                        │
│  (Spring Boot 3.5 RestClient with interceptors)             │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│            HttpClient Configuration Layer                    │
│  • Connection Pool Manager (200 total, 50 per route)        │
│  • Retry Strategy (3 attempts, 1s interval)                 │
│  • Keep-Alive Strategy (30s default)                        │
│  • Timeout Configuration (connect, socket, request)         │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│              Apache HttpClient5 Layer                        │
│  • Connection pooling and lifecycle management               │
│  • Stale connection checking                                │
│  • Idle connection eviction                                 │
│  • Connection validation                                    │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│                   WireMock Server                            │
│  • Simulates various failure scenarios                       │
│  • HTTP/HTTPS endpoints                                     │
│  • Fault injection (faults, delays, errors)                 │
└─────────────────────────────────────────────────────────────┘
```

## Prerequisites

- **Java 21** or higher
- **Maven 3.8+** or Gradle 8+
- **Spring Boot 3.5.0-M1** (or latest 3.5.x)

## Quick Start

### 1. Clone and Build

```bash
cd wiremock-integration-tests
mvn clean install
```

### 2. Run All Tests

```bash
mvn test
```

### 3. Run Specific Test Class

```bash
mvn test -Dtest=StaleConnectionTest
mvn test -Dtest=ConnectionTimeoutTest
mvn test -Dtest=SocketTimeoutTest
mvn test -Dtest=SslErrorTest
mvn test -Dtest=ConnectionResetTest
mvn test -Dtest=ServerErrorTest
```

### 4. Run Application

```bash
mvn spring-boot:run
```

Access metrics at: `http://localhost:8080/actuator/prometheus`

## Configuration

### Connection Pool Configuration

```yaml
http:
  client:
    connection:
      pool:
        max-total: 200                    # Maximum total connections
        max-per-route: 50                 # Maximum connections per route
        validate-after-inactivity-ms: 2000 # Validate after 2s inactivity
        evict-idle-connections-after-ms: 60000 # Evict after 60s idle
```

### Timeout Configuration

```yaml
http:
  client:
    connection:
      timeout:
        connect-ms: 3000                  # Connection timeout: 3s
        socket-ms: 5000                   # Socket/read timeout: 5s
        connection-request-ms: 1000       # Pool checkout timeout: 1s
```

### Retry Configuration

```yaml
http:
  client:
    connection:
      retry:
        enabled: true                     # Enable retry handler
        count: 3                          # Number of retry attempts
        interval-ms: 1000                 # Interval between retries
```

### Keep-Alive Configuration

```yaml
http:
  client:
    connection:
      keep-alive:
        enabled: true                     # Enable keep-alive
        duration-ms: 30000                # Keep-alive duration: 30s
      stale-connection-check: true        # Enable stale connection checking
```

## Test Coverage

### 1. Stale Connection Tests (`StaleConnectionTest.java`)

Simulates scenarios where connections in the pool become stale:

| Test Case | Scenario | Expected Outcome |
|-----------|----------|------------------|
| `testStaleConnectionWithEmptyResponse` | Connection returns empty response | `NoHttpResponseException` wrapped in `ResourceAccessException` |
| `testStaleConnectionRetrySuccess` | First request fails, retry succeeds | Request succeeds on retry |
| `testMultipleStaleConnectionsInSequence` | Sequential stale connections | Each handled appropriately |
| `testStaleConnectionAfterKeepAliveTimeout` | Keep-alive expires | Stale connection detected |
| `testConnectionValidationAfterInactivity` | Connection idle validation | Connection validated before reuse |
| `testExhaustedRetriesOnStaleConnection` | Persistent stale connections | All retries exhausted |

**WireMock Fault:** `Fault.EMPTY_RESPONSE`

### 2. Connection Timeout Tests (`ConnectionTimeoutTest.java`)

Tests connection establishment timeouts:

| Test Case | Scenario | Expected Outcome |
|-----------|----------|------------------|
| `testConnectionTimeoutUnreachableServer` | Unreachable host | `ConnectTimeoutException` |
| `testConnectionTimeoutStoppedServer` | Stopped server | Connection refused/timeout |
| `testConnectionTimeoutRetrySuccess` | Retry on timeout | Retry mechanism verified |
| `testConnectionPoolExhaustion` | Pool exhaustion | Graceful handling |
| `testConnectionTimeoutRespected` | Timeout configuration | Timeout duration respected |

**Test Approach:** Uses unreachable IPs (192.0.2.1) and stopped servers

### 3. Socket Timeout Tests (`SocketTimeoutTest.java`)

Tests read/socket timeout scenarios:

| Test Case | Scenario | Expected Outcome |
|-----------|----------|------------------|
| `testSocketTimeoutOnSlowResponse` | Server delays beyond timeout | `SocketTimeoutException` |
| `testSuccessWithinSocketTimeout` | Response within timeout | Success |
| `testSocketTimeoutRetrySuccess` | Retry after timeout | Succeeds on retry |
| `testSocketTimeoutOnSlowDataTransfer` | Slow chunked transfer | Timeout on slow read |
| `testExhaustedRetriesOnSocketTimeout` | Persistent timeouts | All retries exhausted |
| `testVaryingResponseDelays` | Different delay scenarios | Correct timeout behavior |

**WireMock Configuration:** `withFixedDelay()`, `withChunkedDribbleDelay()`

### 4. SSL Error Tests (`SslErrorTest.java`)

Tests SSL/TLS failure scenarios:

| Test Case | Scenario | Expected Outcome |
|-----------|----------|------------------|
| `testSslHandshakeFailureWithSelfSignedCert` | Self-signed certificate | `SSLHandshakeException` |
| `testSslErrorOnPostRequest` | SSL error on POST | SSL exception |
| `testHttpVsHttpsEndpoints` | Protocol differentiation | HTTP succeeds, HTTPS fails |
| `testSslErrorsNotRetried` | SSL retry behavior | No retries on SSL errors |
| `testSslErrorLogging` | Error logging | Detailed error information |

**WireMock Configuration:** HTTPS endpoints with self-signed certificates

### 5. Connection Reset Tests (`ConnectionResetTest.java`)

Tests connection reset scenarios:

| Test Case | Scenario | Expected Outcome |
|-----------|----------|------------------|
| `testConnectionResetByPeer` | TCP RST packet | `SocketException`/`EOFException` |
| `testConnectionResetRetrySuccess` | Retry after reset | Succeeds on retry |
| `testRandomDataThenClose` | Random data then close | Connection error |
| `testMalformedResponseChunk` | Malformed HTTP response | Parsing/connection error |
| `testRecoveryFromTransientResets` | Transient resets | Recovery after retries |

**WireMock Faults:** `Fault.CONNECTION_RESET_BY_PEER`, `Fault.RANDOM_DATA_THEN_CLOSE`, `Fault.MALFORMED_RESPONSE_CHUNK`

### 6. Server Error Tests (`ServerErrorTest.java`)

Tests HTTP 5xx status code handling:

| Test Case | Scenario | Expected Outcome |
|-----------|----------|------------------|
| `testInternalServerError` | 500 status | `HttpServerErrorException` with 500 |
| `testBadGateway` | 502 status | `HttpServerErrorException` with 502 |
| `testServiceUnavailable` | 503 status | `HttpServerErrorException` with 503 |
| `testGatewayTimeout` | 504 status | `HttpServerErrorException` with 504 |
| `testServerErrorRetrySuccess` | Retry after 500 | Succeeds on retry |
| `testErrorResponseParsing` | Detailed error response | Error details parsed |
| `testDifferentServerErrorCodes` | All 5xx codes | Correct status code detection |

**WireMock Configuration:** `withStatus(5xx)`

## Running Tests

### Run All Tests

```bash
mvn test
```

### Run with Coverage

```bash
mvn test jacoco:report
```

Coverage report: `target/site/jacoco/index.html`

### Run Specific Test Category

```bash
# Stale connection tests
mvn test -Dtest=StaleConnectionTest

# Timeout tests
mvn test -Dtest=ConnectionTimeoutTest,SocketTimeoutTest

# Error tests
mvn test -Dtest=ServerErrorTest,SslErrorTest
```

### Run with Specific Log Level

```bash
mvn test -Dlogging.level.org.apache.hc.client5=DEBUG
```

### Run with Custom Configuration

```bash
mvn test -Dspring.profiles.active=test -Dhttp.client.connection.timeout.connect-ms=5000
```

## Key Components

### 1. HttpClientConfiguration

**Location:** `src/main/java/com/orchestrator/wiremock/config/HttpClientConfiguration.java`

Provides production-grade Apache HttpClient5 configuration:

```java
@Bean
public PoolingHttpClientConnectionManager poolingConnectionManager() {
    ConnectionConfig connectionConfig = ConnectionConfig.custom()
        .setConnectTimeout(Timeout.ofMilliseconds(connectMs))
        .setSocketTimeout(Timeout.ofMilliseconds(socketMs))
        .setValidateAfterInactivity(TimeValue.ofMilliseconds(validateMs))
        .build();

    return PoolingHttpClientConnectionManagerBuilder.create()
        .setMaxConnTotal(maxTotal)
        .setMaxConnPerRoute(maxPerRoute)
        .setDefaultConnectionConfig(connectionConfig)
        .build();
}
```

**Key Features:**
- Connection pool management
- Stale connection checking
- Idle connection eviction
- Retry strategy
- Keep-alive strategy
- Metrics integration

### 2. RestClientConfiguration

**Location:** `src/main/java/com/orchestrator/wiremock/config/RestClientConfiguration.java`

Configures Spring Boot 3.5 RestClient with request/response interceptors:

```java
@Bean
public RestClient restClient() {
    return RestClient.builder()
        .requestFactory(requestFactory)
        .requestInterceptor((request, body, execution) -> {
            // Request/response logging
            // Metrics tracking
            // Error handling
        })
        .build();
}
```

### 3. ExternalApiClient

**Location:** `src/main/java/com/orchestrator/wiremock/client/ExternalApiClient.java`

Sample REST client service demonstrating usage:

```java
@Service
public class ExternalApiClient {
    public String get(String url) {
        return restClient.get()
            .uri(url)
            .retrieve()
            .body(String.class);
    }
}
```

### 4. BaseWireMockTest

**Location:** `src/test/java/com/orchestrator/wiremock/BaseWireMockTest.java`

Base class for all WireMock tests:

```java
@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseWireMockTest {
    protected WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(
            WireMockConfiguration.options().dynamicPort()
        );
        wireMockServer.start();
    }
}
```

## Exception Handling

### Exception Hierarchy

```
ResourceAccessException (Spring)
├── NoHttpResponseException (Apache HttpClient5)
│   └── Stale connection in pool
├── ConnectTimeoutException (Apache HttpClient5)
│   └── Connection establishment timeout
├── SocketTimeoutException (Java)
│   └── Read/socket timeout
├── SSLException (Java)
│   ├── SSLHandshakeException
│   └── SSLPeerUnverifiedException
├── SocketException (Java)
│   ├── ConnectionResetException
│   └── EOFException
└── ConnectionClosedException (Apache HttpClient5)

HttpServerErrorException (Spring)
├── 500 Internal Server Error
├── 502 Bad Gateway
├── 503 Service Unavailable
└── 504 Gateway Timeout
```

### Exception Handling Best Practices

1. **Catch Specific Exceptions**
```java
try {
    return apiClient.get(url);
} catch (ResourceAccessException ex) {
    if (ex.getCause() instanceof ConnectTimeoutException) {
        // Handle connection timeout
    } else if (ex.getCause() instanceof SocketTimeoutException) {
        // Handle read timeout
    }
}
```

2. **Implement Circuit Breaker Pattern**
```java
@CircuitBreaker(name = "externalApi", fallbackMethod = "fallback")
public String callExternalApi(String url) {
    return apiClient.get(url);
}
```

3. **Use Retry with Backoff**
```java
@Retry(name = "externalApi",
       maxAttempts = 3,
       waitDuration = Duration.ofMillis(1000))
public String callExternalApi(String url) {
    return apiClient.get(url);
}
```

## Best Practices

### Connection Pool Sizing

**Formula:** `Max Connections = (CPU Cores * 2) + Effective Spindle Count`

**Example Configuration:**
```yaml
# For 8-core system with HDD
max-total: 200      # (8 * 2) + effective spindles
max-per-route: 50   # 25% of max-total
```

### Timeout Configuration

| Timeout Type | Recommended Value | Use Case |
|-------------|-------------------|----------|
| Connect Timeout | 2-5 seconds | Time to establish TCP connection |
| Socket Timeout | 5-30 seconds | Time to receive response data |
| Connection Request | 0.5-2 seconds | Time to get connection from pool |

### Retry Strategy

**Idempotent Methods (Safe to Retry):**
- GET, HEAD, OPTIONS, TRACE, PUT, DELETE

**Non-Idempotent Methods (Careful with Retry):**
- POST (may create duplicates)

```java
// Configure retry for idempotent methods only
.setRetryStrategy(new DefaultHttpRequestRetryStrategy(3, TimeValue.ofSeconds(1)) {
    @Override
    public boolean retryRequest(HttpRequest request, IOException exception, int execCount, HttpContext context) {
        // Only retry idempotent methods
        return request.getMethod().equals("GET") && super.retryRequest(request, exception, execCount, context);
    }
});
```

### Keep-Alive Configuration

```yaml
# Server-side
Connection: keep-alive
Keep-Alive: timeout=60, max=100

# Client-side
keep-alive:
  enabled: true
  duration-ms: 30000  # Shorter than server timeout
```

## Metrics and Monitoring

### Available Metrics

**Connection Pool Metrics:**
```
http.client.pool.total.max             # Maximum total connections
http.client.pool.total.available       # Available connections
```

**Request Metrics:**
```
http.client.requests                   # Request count and duration
  - method: GET|POST|PUT|DELETE
  - status: 200|500|...
  - outcome: SUCCESS|ERROR
  - exception: ConnectTimeoutException|...
```

**Error Metrics:**
```
http.client.errors                     # Error count by type
  - exception: ConnectTimeoutException|SocketTimeoutException|...
```

**Retry Metrics:**
```
http.client.retry                      # Retry count by exception
  - exception: NoHttpResponseException|...
```

### Prometheus Queries

```promql
# Request rate
rate(http_client_requests_total[5m])

# Error rate
rate(http_client_errors_total[5m])

# Average response time
rate(http_client_requests_seconds_sum[5m])
  / rate(http_client_requests_seconds_count[5m])

# Connection pool utilization
http_client_pool_total_max - http_client_pool_total_available
```

### Grafana Dashboard

Create dashboard with panels for:
1. Request throughput (req/s)
2. Error rate (%)
3. Response time (p50, p95, p99)
4. Connection pool utilization
5. Retry rate
6. Timeout occurrences

## Troubleshooting

### Common Issues

#### 1. Connection Pool Exhaustion

**Symptom:** `ConnectionRequestTimeoutException`

**Solution:**
```yaml
http.client.connection.pool.max-total: 500  # Increase pool size
http.client.connection.timeout.connection-request-ms: 5000  # Increase timeout
```

#### 2. Stale Connection Errors

**Symptom:** Frequent `NoHttpResponseException`

**Solution:**
```yaml
http.client.connection.pool.validate-after-inactivity-ms: 1000  # More aggressive validation
http.client.connection.pool.evict-idle-connections-after-ms: 30000  # Faster eviction
```

#### 3. Timeout Issues

**Symptom:** Requests timing out frequently

**Solution:**
```yaml
# Increase timeouts
http.client.connection.timeout.connect-ms: 10000
http.client.connection.timeout.socket-ms: 30000

# Or implement circuit breaker
@CircuitBreaker(name = "api", fallbackMethod = "fallback")
```

#### 4. SSL Errors

**Symptom:** `SSLHandshakeException`

**Solution:**
```java
// Configure trust store
SSLContext sslContext = SSLContextBuilder.create()
    .loadTrustMaterial(trustStore, new TrustSelfSignedStrategy())
    .build();

connectionManager.setSSLContext(sslContext);
```

### Debug Logging

Enable debug logging for troubleshooting:

```yaml
logging:
  level:
    org.apache.hc.client5: DEBUG
    org.apache.hc.client5.http.wire: TRACE  # Full request/response
    com.orchestrator: DEBUG
```

### Health Checks

Monitor application health:

```bash
curl http://localhost:8080/actuator/health
```

## License

MIT License - See LICENSE file for details

## Contributing

Contributions welcome! Please follow the contribution guidelines.

## Support

For issues and questions:
- GitHub Issues: [Create an issue](https://github.com/your-org/wiremock-integration-tests/issues)
- Documentation: [Wiki](https://github.com/your-org/wiremock-integration-tests/wiki)

---

**Built with:**
- Spring Boot 3.5
- Apache HttpClient5
- WireMock 3.x
- Java 21
- JUnit 5
- AssertJ
