package com.yourorg.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "kafka.consumer")
public class KafkaConsumerProperties {

    // 🔹 Core Kafka configs
    private String bootstrapServers = "localhost:9092";
    private String groupId = "default-group";
    private String topic = "payments-topic";
    private boolean enableAutoCommit = false;
    private String autoOffsetReset = "latest";
    private int maxPollRecords = 1000;
    private int concurrency = 3;

    // 🔹 Batch toggle
    private boolean batchEnabled = true; // ✅ default = true

    // 🔹 Consumer timeout & heartbeat tuning
    private int sessionTimeoutMs = 45000;        // Default 45s
    private int heartbeatIntervalMs = 15000;     // Default 15s
    private int maxPollIntervalMs = 300000;      // Default 5 min

    // 🔹 Fetch tuning
    private int fetchMinBytes = 1048576;         // 1 MB
    private int fetchMaxBytes = 52428800;        // 50 MB
    private int fetchMaxWaitMs = 500;            // Wait up to 500ms for more data

    // 🔹 Offset & commit control
    private boolean isolationReadCommitted = true;  // For transactional producers
    private boolean autoCommitSync = false;         // Manual offset commit style
    private int autoCommitIntervalMs = 5000;        // If auto commit enabled

    // 🔹 Network tuning
    private int requestTimeoutMs = 30000;
    private int retryBackoffMs = 100;
    private int reconnectBackoffMs = 1000;

    // 🔹 Cluster/Instance identification (for OCP multi-cluster)
    private String clientId = "default-client";
    private String groupInstanceId = ""; // Used for static membership

    // 🔹 Dynamic extra configs (optional)
    private Map<String, Object> extra = new HashMap<>();

    // --- Getters and Setters ---

    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public boolean isEnableAutoCommit() { return enableAutoCommit; }
    public void setEnableAutoCommit(boolean enableAutoCommit) { this.enableAutoCommit = enableAutoCommit; }

    public String getAutoOffsetReset() { return autoOffsetReset; }
    public void setAutoOffsetReset(String autoOffsetReset) { this.autoOffsetReset = autoOffsetReset; }

    public int getMaxPollRecords() { return maxPollRecords; }
    public void setMaxPollRecords(int maxPollRecords) { this.maxPollRecords = maxPollRecords; }

    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }

    public boolean isBatchEnabled() { return batchEnabled; }
    public void setBatchEnabled(boolean batchEnabled) { this.batchEnabled = batchEnabled; }

    public int getSessionTimeoutMs() { return sessionTimeoutMs; }
    public void setSessionTimeoutMs(int sessionTimeoutMs) { this.sessionTimeoutMs = sessionTimeoutMs; }

    public int getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
    public void setHeartbeatIntervalMs(int heartbeatIntervalMs) { this.heartbeatIntervalMs = heartbeatIntervalMs; }

    public int getMaxPollIntervalMs() { return maxPollIntervalMs; }
    public void setMaxPollIntervalMs(int maxPollIntervalMs) { this.maxPollIntervalMs = maxPollIntervalMs; }

    public int getFetchMinBytes() { return fetchMinBytes; }
    public void setFetchMinBytes(int fetchMinBytes) { this.fetchMinBytes = fetchMinBytes; }

    public int getFetchMaxBytes() { return fetchMaxBytes; }
    public void setFetchMaxBytes(int fetchMaxBytes) { this.fetchMaxBytes = fetchMaxBytes; }

    public int getFetchMaxWaitMs() { return fetchMaxWaitMs; }
    public void setFetchMaxWaitMs(int fetchMaxWaitMs) { this.fetchMaxWaitMs = fetchMaxWaitMs; }

    public boolean isIsolationReadCommitted() { return isolationReadCommitted; }
    public void setIsolationReadCommitted(boolean isolationReadCommitted) { this.isolationReadCommitted = isolationReadCommitted; }

    public boolean isAutoCommitSync() { return autoCommitSync; }
    public void setAutoCommitSync(boolean autoCommitSync) { this.autoCommitSync = autoCommitSync; }

    public int getAutoCommitIntervalMs() { return autoCommitIntervalMs; }
    public void setAutoCommitIntervalMs(int autoCommitIntervalMs) { this.autoCommitIntervalMs = autoCommitIntervalMs; }

    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }

    public int getRetryBackoffMs() { return retryBackoffMs; }
    public void setRetryBackoffMs(int retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }

    public int getReconnectBackoffMs() { return reconnectBackoffMs; }
    public void setReconnectBackoffMs(int reconnectBackoffMs) { this.reconnectBackoffMs = reconnectBackoffMs; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getGroupInstanceId() { return groupInstanceId; }
    public void setGroupInstanceId(String groupInstanceId) { this.groupInstanceId = groupInstanceId; }

    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> extra) { this.extra = extra; }
}
kafka:
  consumer:
    bootstrap-servers: prod-broker1:9092,prod-broker2:9092
    group-id: payments-consumer
    topic: payments-topic
    concurrency: 8
    batch-enabled: true
    enable-auto-commit: false
    auto-offset-reset: earliest
    session-timeout-ms: 45000
    heartbeat-interval-ms: 15000
    max-poll-interval-ms: 300000
    max-poll-records: 2000
    fetch-min-bytes: 1048576
    fetch-max-bytes: 52428800
    fetch-max-wait-ms: 1000
    retry-backoff-ms: 100
    reconnect-backoff-ms: 1000
    client-id: ${CLUSTER_ID}-${HOSTNAME}
    group-instance-id: ${CLUSTER_ID}-${HOSTNAME}
    extra:
      isolation.level: read_committed
package com.yourorg.kafka.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, String> consumerFactory(KafkaConsumerProperties props) {
        Map<String, Object> config = new HashMap<>();

        // 🔹 Core Connection
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, props.getGroupId());
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, props.getClientId());
        if (props.getGroupInstanceId() != null && !props.getGroupInstanceId().isEmpty()) {
            config.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, props.getGroupInstanceId());
        }

        // 🔹 Core Consumer Behavior
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, props.isEnableAutoCommit());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, props.getAutoOffsetReset());
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, props.getMaxPollRecords());

        // 🔹 Polling & Heartbeat Tuning
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, props.getMaxPollIntervalMs());
        config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, props.getSessionTimeoutMs());
        config.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, props.getHeartbeatIntervalMs());

        // 🔹 Fetch & Network Performance
        config.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, props.getFetchMinBytes());
        config.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, props.getFetchMaxBytes());
        config.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, props.getFetchMaxWaitMs());
        config.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, props.getRequestTimeoutMs());
        config.put(ConsumerConfig.RETRY_BACKOFF_MS_CONFIG, props.getRetryBackoffMs());
        config.put(ConsumerConfig.RECONNECT_BACKOFF_MS_CONFIG, props.getReconnectBackoffMs());

        // 🔹 Isolation Level & Commit Strategy
        if (props.isIsolationReadCommitted()) {
            config.put("isolation.level", "read_committed");
        } else {
            config.put("isolation.level", "read_uncommitted");
        }

        if (props.isEnableAutoCommit()) {
            config.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, props.getAutoCommitIntervalMs());
        }

        // 🔹 Deserializers
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // 🔹 Merge extra configs (dynamic extensions)
        config.putAll(props.getExtra());

        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaConsumerProperties props,
            KafkaTemplate<String, String> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // 🔹 Batch vs. Single Message Mode
        factory.setBatchListener(props.isBatchEnabled());

        // 🔹 Concurrency
        factory.setConcurrency(props.getConcurrency());

        // 🔹 Acknowledgment Strategy
        factory.getContainerProperties().setAckMode(
                props.isBatchEnabled()
                        ? ContainerProperties.AckMode.MANUAL   // commit after batch success
                        : ContainerProperties.AckMode.MANUAL_IMMEDIATE // commit per record
        );

        // 🔹 Error Handling and DLQ (Optional)
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(1000L, 3L) // retry every 1s, up to 3 times
        );
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}
