# Testing Guide - WireMock Integration Tests

This guide provides detailed information about the test suite structure, test scenarios, and how to extend the tests.

## Table of Contents

1. [Test Structure](#test-structure)
2. [Test Scenarios](#test-scenarios)
3. [Writing New Tests](#writing-new-tests)
4. [WireMock Scenarios](#wiremock-scenarios)
5. [Assertions and Verifications](#assertions-and-verifications)
6. [Debugging Tests](#debugging-tests)
7. [Performance Testing](#performance-testing)

## Test Structure

### Base Test Class

All integration tests extend `BaseWireMockTest`:

```java
@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseWireMockTest {
    protected WireMockServer wireMockServer;
    protected String baseUrl;

    @Autowired
    protected ExternalApiClient apiClient;
}
```

### Test Organization

```
src/test/java/com/orchestrator/wiremock/tests/
├── StaleConnectionTest.java          # Stale connection scenarios
├── ConnectionTimeoutTest.java        # Connection timeout scenarios
├── SocketTimeoutTest.java            # Socket/read timeout scenarios
├── SslErrorTest.java                 # SSL/TLS error scenarios
├── ConnectionResetTest.java          # Connection reset scenarios
└── ServerErrorTest.java              # HTTP 5xx error scenarios
```

## Test Scenarios

### 1. Stale Connection Tests

**File:** `StaleConnectionTest.java`

**Scenarios:**

#### 1.1 Empty Response (Stale Connection)
```java
stubFor(get(urlEqualTo("/api/stale"))
    .willReturn(aResponse()
        .withFault(Fault.EMPTY_RESPONSE)));
```

**Expected:** `ResourceAccessException` wrapping `NoHttpResponseException`

#### 1.2 Retry on Stale Connection
```java
stubFor(get(urlEqualTo("/api/retry"))
    .inScenario("Stale Retry")
    .whenScenarioStateIs("Started")
    .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE))
    .willSetStateTo("Failed"));

stubFor(get(urlEqualTo("/api/retry"))
    .inScenario("Stale Retry")
    .whenScenarioStateIs("Failed")
    .willReturn(aResponse().withStatus(200)));
```

**Expected:** Success after retry

#### 1.3 Connection Validation After Inactivity
```java
// Make first request
apiClient.get(url("/api/validate"));

// Wait for validation period
Thread.sleep(1500);

// Make second request (triggers validation)
apiClient.get(url("/api/validate"));
```

**Expected:** Connection validated before reuse

### 2. Connection Timeout Tests

**File:** `ConnectionTimeoutTest.java`

**Scenarios:**

#### 2.1 Unreachable Server
```java
String unreachableUrl = "http://192.0.2.1:8080/api/test";
apiClient.get(unreachableUrl);
```

**Expected:** `ConnectTimeoutException` after configured timeout

#### 2.2 Stopped Server
```java
wireMockServer.stop();
apiClient.get("http://localhost:" + port + "/api/test");
wireMockServer.start();
```

**Expected:** Connection refused or timeout

### 3. Socket Timeout Tests

**File:** `SocketTimeoutTest.java`

**Scenarios:**

#### 3.1 Fixed Delay Beyond Timeout
```java
stubFor(get(urlEqualTo("/api/slow"))
    .willReturn(aResponse()
        .withStatus(200)
        .withFixedDelay(5000)));  // Exceeds 3s timeout
```

**Expected:** `SocketTimeoutException`

#### 3.2 Chunked Dribble Delay
```java
stubFor(get(urlEqualTo("/api/slow-transfer"))
    .willReturn(aResponse()
        .withStatus(200)
        .withBody(largeBody)
        .withChunkedDribbleDelay(10, 5000)));
```

**Expected:** Timeout while reading response

#### 3.3 Success Within Timeout
```java
stubFor(get(urlEqualTo("/api/fast"))
    .willReturn(aResponse()
        .withStatus(200)
        .withFixedDelay(1000)));  // Within 3s timeout
```

**Expected:** Success

### 4. SSL Error Tests

**File:** `SslErrorTest.java`

**Scenarios:**

#### 4.1 Self-Signed Certificate
```java
String httpsUrl = "https://localhost:" + wireMockServer.httpsPort() + "/api/secure";
apiClient.get(httpsUrl);
```

**Expected:** `SSLHandshakeException` or `SSLException`

#### 4.2 HTTP vs HTTPS
```java
// HTTP - succeeds
apiClient.get("http://localhost:" + port + "/api/test");

// HTTPS - fails
apiClient.get("https://localhost:" + httpsPort + "/api/test");
```

**Expected:** HTTP succeeds, HTTPS fails with SSL error

### 5. Connection Reset Tests

**File:** `ConnectionResetTest.java`

**Scenarios:**

#### 5.1 Connection Reset by Peer
```java
stubFor(get(urlEqualTo("/api/reset"))
    .willReturn(aResponse()
        .withFault(Fault.CONNECTION_RESET_BY_PEER)));
```

**Expected:** `SocketException` or `EOFException`

#### 5.2 Random Data Then Close
```java
stubFor(get(urlEqualTo("/api/random"))
    .willReturn(aResponse()
        .withFault(Fault.RANDOM_DATA_THEN_CLOSE)));
```

**Expected:** Connection or parsing error

#### 5.3 Malformed Response Chunk
```java
stubFor(get(urlEqualTo("/api/malformed"))
    .willReturn(aResponse()
        .withFault(Fault.MALFORMED_RESPONSE_CHUNK)));
```

**Expected:** Connection or parsing error

### 6. Server Error Tests

**File:** `ServerErrorTest.java`

**Scenarios:**

#### 6.1 Internal Server Error (500)
```java
stubFor(get(urlEqualTo("/api/error-500"))
    .willReturn(aResponse()
        .withStatus(500)
        .withBody("{\"error\":\"Internal Server Error\"}")));
```

**Expected:** `HttpServerErrorException` with status 500

#### 6.2 Service Unavailable (503)
```java
stubFor(get(urlEqualTo("/api/error-503"))
    .willReturn(aResponse()
        .withStatus(503)
        .withHeader("Retry-After", "60")
        .withBody("{\"error\":\"Service Unavailable\"}")));
```

**Expected:** `HttpServerErrorException` with status 503

## Writing New Tests

### Template for New Test Class

```java
package com.orchestrator.wiremock.tests;

import com.orchestrator.wiremock.BaseWireMockTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

@Slf4j
@DisplayName("Your Test Suite Name")
class YourNewTest extends BaseWireMockTest {

    @Test
    @DisplayName("Should handle specific scenario")
    void testSpecificScenario() {
        // Given: Configure WireMock
        stubFor(get(urlEqualTo("/api/endpoint"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("{\"result\":\"success\"}")));

        // When: Execute request
        String response = apiClient.get(url("/api/endpoint"));

        // Then: Verify outcome
        assertThat(response).contains("success");
        verify(1, getRequestedFor(urlEqualTo("/api/endpoint")));

        log.info("Test completed successfully");
    }
}
```

### Best Practices for Writing Tests

1. **Use Descriptive Test Names**
```java
@DisplayName("Should retry on connection timeout and succeed on second attempt")
void testConnectionTimeoutRetrySuccess() { }
```

2. **Follow Given-When-Then Pattern**
```java
// Given: Setup preconditions
stubFor(...);

// When: Execute action
String response = apiClient.get(...);

// Then: Verify outcome
assertThat(response).contains(...);
verify(...);
```

3. **Log Test Progress**
```java
log.info("Successfully verified retry mechanism");
```

4. **Verify WireMock Interactions**
```java
verify(2, getRequestedFor(urlEqualTo("/api/endpoint")));
```

5. **Use Scenarios for State**
```java
stubFor(get(urlEqualTo("/api/test"))
    .inScenario("Test Scenario")
    .whenScenarioStateIs("Started")
    .willReturn(...)
    .willSetStateTo("Next State"));
```

## WireMock Scenarios

### Scenario States

WireMock scenarios allow testing stateful interactions:

```java
// First request - state: "Started" (default)
stubFor(get(urlEqualTo("/api/resource"))
    .inScenario("Multi-Step")
    .whenScenarioStateIs("Started")
    .willReturn(aResponse().withStatus(503))
    .willSetStateTo("First Attempt"));

// Second request - state: "First Attempt"
stubFor(get(urlEqualTo("/api/resource"))
    .inScenario("Multi-Step")
    .whenScenarioStateIs("First Attempt")
    .willReturn(aResponse().withStatus(200))
    .willSetStateTo("Success"));

// Third request - state: "Success"
stubFor(get(urlEqualTo("/api/resource"))
    .inScenario("Multi-Step")
    .whenScenarioStateIs("Success")
    .willReturn(aResponse().withStatus(200)));
```

### Available Faults

```java
Fault.EMPTY_RESPONSE              // Stale connection
Fault.CONNECTION_RESET_BY_PEER    // TCP RST
Fault.RANDOM_DATA_THEN_CLOSE      // Invalid data
Fault.MALFORMED_RESPONSE_CHUNK    // Invalid HTTP
```

### Response Configuration

```java
aResponse()
    .withStatus(200)                          // Status code
    .withHeader("Content-Type", "application/json")  // Headers
    .withBody("{\"data\":\"value\"}")         // Response body
    .withFixedDelay(1000)                     // Fixed delay
    .withChunkedDribbleDelay(10, 1000)        // Slow transfer
    .withFault(Fault.EMPTY_RESPONSE)          // Fault injection
```

## Assertions and Verifications

### AssertJ Assertions

```java
// String assertions
assertThat(response).contains("success");
assertThat(response).isNotEmpty();

// Exception assertions
assertThatThrownBy(() -> apiClient.get(url))
    .isInstanceOf(ResourceAccessException.class)
    .hasCauseInstanceOf(SocketTimeoutException.class);

// Custom assertions
assertThatThrownBy(() -> apiClient.get(url))
    .satisfies(ex -> {
        assertThat(ex.getCause()).isNotNull();
        assertThat(ex.getMessage()).contains("timeout");
    });

// Numeric assertions
assertThat(duration).isGreaterThan(1000).isLessThan(5000);
```

### WireMock Verifications

```java
// Exact count
verify(1, getRequestedFor(urlEqualTo("/api/test")));
verify(exactly(1), getRequestedFor(urlEqualTo("/api/test")));

// At least / at most
verify(atLeast(1), getRequestedFor(urlEqualTo("/api/test")));
verify(atMost(3), getRequestedFor(urlEqualTo("/api/test")));
verify(lessThanOrExactly(3), getRequestedFor(urlEqualTo("/api/test")));

// Request matching
verify(postRequestedFor(urlEqualTo("/api/create"))
    .withHeader("Content-Type", equalTo("application/json"))
    .withRequestBody(containing("data")));
```

## Debugging Tests

### Enable Debug Logging

```yaml
# src/test/resources/application-test.yml
logging:
  level:
    org.apache.hc.client5: DEBUG
    org.apache.hc.client5.http.wire: TRACE  # Full HTTP traffic
    com.orchestrator: DEBUG
    org.wiremock: DEBUG
```

### Inspect WireMock Requests

```java
@Test
void debugTest() {
    stubFor(get(urlEqualTo("/api/debug"))
        .willReturn(aResponse().withStatus(200)));

    apiClient.get(url("/api/debug"));

    // Get all requests
    List<LoggedRequest> requests = wireMockServer.findAll(
        getRequestedFor(urlEqualTo("/api/debug"))
    );

    requests.forEach(req -> {
        log.info("Request: {} {}", req.getMethod(), req.getUrl());
        log.info("Headers: {}", req.getHeaders());
        log.info("Body: {}", req.getBodyAsString());
    });
}
```

### Measure Timing

```java
@Test
void timingTest() {
    long startTime = System.currentTimeMillis();

    try {
        apiClient.get(url("/api/test"));
    } catch (Exception e) {
        // Expected
    }

    long duration = System.currentTimeMillis() - startTime;
    log.info("Request took {}ms", duration);

    assertThat(duration).isGreaterThan(2000).isLessThan(5000);
}
```

### Debug Connection Pool

```java
@Autowired
private PoolingHttpClientConnectionManager connectionManager;

@Test
void poolDebugTest() {
    log.info("Pool stats: {}", connectionManager.getTotalStats());

    apiClient.get(url("/api/test"));

    log.info("Pool stats after request: {}", connectionManager.getTotalStats());
}
```

## Performance Testing

### Load Testing Template

```java
@Test
void loadTest() throws InterruptedException {
    stubFor(get(urlEqualTo("/api/load"))
        .willReturn(aResponse()
            .withStatus(200)
            .withFixedDelay(100)));

    int threads = 10;
    int requestsPerThread = 100;
    CountDownLatch latch = new CountDownLatch(threads);

    for (int i = 0; i < threads; i++) {
        new Thread(() -> {
            for (int j = 0; j < requestsPerThread; j++) {
                try {
                    apiClient.get(url("/api/load"));
                } catch (Exception e) {
                    log.error("Request failed", e);
                }
            }
            latch.countDown();
        }).start();
    }

    latch.await();

    verify(threads * requestsPerThread,
        getRequestedFor(urlEqualTo("/api/load")));
}
```

### Connection Pool Stress Test

```java
@Test
void poolStressTest() {
    stubFor(get(urlEqualTo("/api/pool"))
        .willReturn(aResponse().withStatus(200)));

    // Make more requests than pool size
    for (int i = 0; i < 300; i++) {
        apiClient.get(url("/api/pool"));
    }

    // Verify connection pool handled load
    verify(300, getRequestedFor(urlEqualTo("/api/pool")));
}
```

## Continuous Integration

### Maven Command for CI

```bash
# Run all tests with coverage
mvn clean test jacoco:report

# Run specific test suite
mvn test -Dtest=StaleConnectionTest

# Run with timeout
mvn test -Dmaven.test.failure.ignore=false
```

### GitHub Actions Example

```yaml
name: Integration Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK 21
        uses: actions/setup-java@v2
        with:
          java-version: '21'
      - name: Run tests
        run: mvn test
      - name: Generate coverage report
        run: mvn jacoco:report
```

## Summary

- All tests extend `BaseWireMockTest`
- Follow Given-When-Then pattern
- Use descriptive test names with `@DisplayName`
- Verify both responses and WireMock interactions
- Log test progress for debugging
- Use scenarios for stateful testing
- Enable debug logging when troubleshooting
- Measure timing for timeout tests
- Write performance tests for connection pool
