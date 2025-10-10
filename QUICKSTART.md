# Quick Start Guide

Get up and running with the WireMock integration tests in 5 minutes.

## Prerequisites

Ensure you have:
- Java 21 installed
- Maven 3.8+ installed

Verify:
```bash
java -version   # Should show Java 21
mvn -version    # Should show Maven 3.8+
```

## 1. Build the Project

```bash
cd wiremock-integration-tests
mvn clean install
```

**Expected output:**
```
[INFO] BUILD SUCCESS
[INFO] Total time: 45 s
```

## 2. Run All Tests

```bash
mvn test
```

**Expected output:**
```
[INFO] Tests run: 50+, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 3. Run Specific Test Suite

### Stale Connection Tests
```bash
mvn test -Dtest=StaleConnectionTest
```

### Connection Timeout Tests
```bash
mvn test -Dtest=ConnectionTimeoutTest
```

### Socket Timeout Tests
```bash
mvn test -Dtest=SocketTimeoutTest
```

### SSL Error Tests
```bash
mvn test -Dtest=SslErrorTest
```

### Connection Reset Tests
```bash
mvn test -Dtest=ConnectionResetTest
```

### Server Error Tests
```bash
mvn test -Dtest=ServerErrorTest
```

## 4. View Test Results

After running tests, view the surefire report:

```bash
# Open in browser
open target/surefire-reports/index.html  # macOS
xdg-open target/surefire-reports/index.html  # Linux
start target/surefire-reports/index.html  # Windows
```

## 5. Run the Application

Start the Spring Boot application:

```bash
mvn spring-boot:run
```

**Access endpoints:**
- Health: http://localhost:8080/actuator/health
- Metrics: http://localhost:8080/actuator/metrics
- Prometheus: http://localhost:8080/actuator/prometheus

## Project Structure

```
wiremock/
├── src/
│   ├── main/
│   │   ├── java/com/orchestrator/wiremock/
│   │   │   ├── WireMockIntegrationApplication.java   # Main application
│   │   │   ├── client/
│   │   │   │   └── ExternalApiClient.java            # Sample REST client
│   │   │   └── config/
│   │   │       ├── HttpClientConfiguration.java      # HttpClient5 config
│   │   │       ├── HttpClientProperties.java         # Configuration properties
│   │   │       └── RestClientConfiguration.java      # RestClient config
│   │   └── resources/
│   │       └── application.yml                       # Main configuration
│   └── test/
│       ├── java/com/orchestrator/wiremock/
│       │   ├── BaseWireMockTest.java                 # Base test class
│       │   └── tests/
│       │       ├── StaleConnectionTest.java          # 7 test cases
│       │       ├── ConnectionTimeoutTest.java        # 7 test cases
│       │       ├── SocketTimeoutTest.java            # 9 test cases
│       │       ├── SslErrorTest.java                 # 9 test cases
│       │       ├── ConnectionResetTest.java          # 11 test cases
│       │       └── ServerErrorTest.java              # 13 test cases
│       └── resources/
│           └── application-test.yml                  # Test configuration
├── pom.xml                                           # Maven dependencies
├── README.md                                         # Full documentation
├── TESTING_GUIDE.md                                  # Testing guide
└── QUICKSTART.md                                     # This file
```

## Test Coverage Summary

| Test Suite | Test Cases | Coverage |
|------------|-----------|----------|
| StaleConnectionTest | 7 | NoHttpResponseException scenarios |
| ConnectionTimeoutTest | 7 | ConnectTimeoutException scenarios |
| SocketTimeoutTest | 9 | SocketTimeoutException scenarios |
| SslErrorTest | 9 | SSL/TLS error scenarios |
| ConnectionResetTest | 11 | Connection reset scenarios |
| ServerErrorTest | 13 | HTTP 5xx error scenarios |
| **Total** | **56** | **All major connection errors** |

## Key Configuration

### Connection Pool (src/main/resources/application.yml)

```yaml
http.client.connection.pool:
  max-total: 200                      # Maximum total connections
  max-per-route: 50                   # Maximum per route
  validate-after-inactivity-ms: 2000  # Validate after inactivity
  evict-idle-connections-after-ms: 60000  # Evict idle connections
```

### Timeouts

```yaml
http.client.connection.timeout:
  connect-ms: 3000      # Connection timeout: 3 seconds
  socket-ms: 5000       # Socket timeout: 5 seconds
  connection-request-ms: 1000  # Pool request timeout: 1 second
```

### Retry

```yaml
http.client.connection.retry:
  enabled: true         # Enable retry
  count: 3              # 3 retry attempts
  interval-ms: 1000     # 1 second between retries
```

## Example Test Output

```
StaleConnectionTest
✓ Should handle stale connection with EMPTY_RESPONSE fault
✓ Should retry on stale connection and succeed on second attempt
✓ Should handle multiple stale connections in sequence
✓ Should handle stale connection after keep-alive timeout
✓ Should validate connections after inactivity period
✓ Should handle stale connection on POST request
✓ Should exhaust retries on persistent stale connections

ConnectionTimeoutTest
✓ Should timeout when server is unreachable
✓ Should timeout when connecting to stopped WireMock server
✓ Should retry on connection timeout and succeed
✓ Should handle connection timeout on POST request
✓ Should handle connection timeout with different HTTP methods
✓ Should respect connection timeout configuration
✓ Should handle connection pool exhaustion gracefully

... (all tests pass)

Tests run: 56, Failures: 0, Errors: 0, Skipped: 0
```

## Common Commands

### Development
```bash
# Clean build
mvn clean install

# Run tests with debug logging
mvn test -Dlogging.level.org.apache.hc.client5=DEBUG

# Skip tests
mvn clean install -DskipTests

# Run specific test method
mvn test -Dtest=StaleConnectionTest#testStaleConnectionWithEmptyResponse
```

### Analysis
```bash
# Generate test coverage report
mvn test jacoco:report
# Report: target/site/jacoco/index.html

# Run checkstyle
mvn checkstyle:check

# Run spotbugs
mvn spotbugs:check
```

### Debugging
```bash
# Run with remote debugging enabled
mvn test -Dmaven.surefire.debug

# Then attach debugger to port 5005
```

## Next Steps

1. **Read the Documentation**
   - [README.md](README.md) - Complete documentation
   - [TESTING_GUIDE.md](TESTING_GUIDE.md) - Detailed testing guide

2. **Explore the Tests**
   - Start with `StaleConnectionTest.java`
   - Understand WireMock fault injection
   - Study retry mechanisms

3. **Customize Configuration**
   - Modify `application.yml` for your needs
   - Adjust timeout values
   - Configure connection pool size

4. **Add Your Own Tests**
   - Extend `BaseWireMockTest`
   - Follow the existing test patterns
   - Add new failure scenarios

5. **Integrate with Your Project**
   - Copy relevant configuration classes
   - Adapt to your use case
   - Customize retry and timeout strategies

## Troubleshooting

### Tests are failing

1. Check Java version:
```bash
java -version  # Must be Java 21
```

2. Clean and rebuild:
```bash
mvn clean install
```

3. Check for port conflicts:
```bash
# WireMock uses dynamic ports, but check if something is blocking
netstat -an | grep LISTEN
```

### Tests are slow

1. Adjust test timeouts in `application-test.yml`:
```yaml
http.client.connection.timeout:
  connect-ms: 1000      # Reduce from 2000
  socket-ms: 2000       # Reduce from 3000
```

2. Run tests in parallel:
```bash
mvn test -T 4  # Use 4 threads
```

### Connection pool issues

1. Check pool configuration:
```yaml
http.client.connection.pool:
  max-total: 50         # Reduce if running on limited resources
  max-per-route: 20
```

2. Enable connection pool metrics:
```bash
# View metrics
curl http://localhost:8080/actuator/metrics/http.client.pool.total.available
```

## Support

- **Documentation:** See [README.md](README.md)
- **Testing Guide:** See [TESTING_GUIDE.md](TESTING_GUIDE.md)
- **Issues:** Check test logs in `target/surefire-reports/`

## Summary

You now have a working WireMock integration test suite that covers:
- ✅ Stale connections
- ✅ Connection timeouts
- ✅ Socket timeouts
- ✅ SSL errors
- ✅ Connection resets
- ✅ Server errors

All with production-grade Apache HttpClient5 configuration!
