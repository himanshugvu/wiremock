package com.yourorg.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * =====================================================================
 *  PaymentConsumer.java  (single-file drop-in)
 *  - High-throughput Kafka consumer with:
 *      • Single & batch processing
 *      • Virtual threads (JDK 21+) for parallel batch work
 *      • Conditional commits (commit only on success OR after error persisted)
 *      • Full traceability (topic, partition, offset, key)
 *  - JDBC error logging with strong data-integrity:
 *      • Idempotency (unique constraint, upsert semantics)
 *      • ACID transactions for single & batch inserts
 * =====================================================================
 *
 * Place in: src/main/java/com/yourorg/kafka/PaymentConsumer.java
 */
@Service
public class PaymentConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentConsumer.class);

    private final KafkaConsumerProperties props;
    private final PaymentService paymentService;
    private final ErrorLogBatchService errorLogBatchService;

    public PaymentConsumer(KafkaConsumerProperties props,
                           PaymentService paymentService,
                           ErrorLogBatchService errorLogBatchService) {
        this.props = props;
        this.paymentService = paymentService;
        this.errorLogBatchService = errorLogBatchService;
    }

    @SuppressWarnings("unchecked")
    @KafkaListener(
            topics = "#{@kafkaConsumerProperties.topic}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(Object message, Acknowledgment ack) {
        if (message instanceof List<?> rawList) {
            List<ConsumerRecord<String, String>> batch =
                    (List<ConsumerRecord<String, String>>) (List<?>) rawList;
            handleBatch(batch, ack);
        } else if (message instanceof ConsumerRecord<?, ?> generic) {
            ConsumerRecord<String, String> record =
                    (ConsumerRecord<String, String>) (ConsumerRecord<?, ?>) generic;
            handleSingle(record, ack);
        } else {
            log.error("Unsupported payload type received by consumer: {}", message == null ? "null" : message.getClass());
        }
    }

    // ------------------------------------------------------------------
    // SINGLE: Commit only if processed OR error row persisted transactionally
    // ------------------------------------------------------------------
    private void handleSingle(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            paymentService.processPayment(record);
            ack.acknowledge();
        } catch (Exception processingEx) {
            log.error("Single processing failed; persisting error. topic={}, partition={}, offset={}, key={}",
                    record.topic(), record.partition(), record.offset(), record.key(), processingEx);

            ErrorRecord err = ErrorRecord.single(record, processingEx);

            try {
                errorLogBatchService.insertSingle(err);   // transactional + idempotent
                log.warn("Error persisted; committing offset (offset={}).", record.offset());
                ack.acknowledge();
            } catch (Exception errorInsertEx) {
                log.error("Error persistence failed; NOT committing offset (offset={}).", record.offset(), errorInsertEx);
                throw new RuntimeException("Processing & error persistence both failed — no commit", errorInsertEx);
            }
        }
    }

    // ------------------------------------------------------------------
    // BATCH: Parallel process, collect failures, bulk-insert errors atomically, commit end
    // ------------------------------------------------------------------
    private void handleBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        if (records.isEmpty()) {
            ack.acknowledge();
            return;
        }

        Set<Integer> partitions = new HashSet<>();
        for (ConsumerRecord<String, String> r : records) partitions.add(r.partition());

        log.info("Processing batch: size={}, topic={}, partitions={}",
                records.size(), records.get(0).topic(), partitions);

        ConcurrentLinkedQueue<ErrorRecord> errorQueue = new ConcurrentLinkedQueue<>();

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Void>> tasks = records.stream()
                    .map(rec -> (Callable<Void>) () -> {
                        try {
                            paymentService.processPayment(rec);
                        } catch (Exception ex) {
                            log.error("Batch record failed: topic={}, partition={}, offset={}",
                                    rec.topic(), rec.partition(), rec.offset(), ex);
                            errorQueue.add(ErrorRecord.batch(rec, ex));
                        }
                        return null;
                    })
                    .toList();

            exec.invokeAll(tasks);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Batch execution interrupted", ie);
        }

        // Persist all failures atomically (transaction); only then commit offsets
        if (!errorQueue.isEmpty()) {
            List<ErrorRecord> toInsert = new ArrayList<>(errorQueue);
            try {
                errorLogBatchService.insertBatch(toInsert);  // transactional + idempotent
                log.warn("Batch error persistence succeeded; count={}", toInsert.size());
            } catch (Exception batchInsertEx) {
                log.error("Batch error persistence failed; NOT committing offsets", batchInsertEx);
                throw new RuntimeException("Batch error persistence failed — no commit", batchInsertEx);
            }
        }

        ack.acknowledge();
        log.info("Batch committed successfully: size={}", records.size());
    }
}

/* =====================================================================
   CONFIGURATION PROPERTIES (centralized, override via application.yml)
   ===================================================================== */
@ConfigurationProperties(prefix = "kafka.consumer")
class KafkaConsumerProperties {

    // Core
    private String bootstrapServers = "localhost:9092";
    private String groupId = "default-group";
    private String topic = "payments-topic";
    private boolean enableAutoCommit = false;
    private String autoOffsetReset = "latest";
    private int maxPollRecords = 1000;
    private int concurrency = 3;

    // Mode
    private boolean batchEnabled = true;

    // Timeouts & heartbeats
    private int sessionTimeoutMs = 45_000;
    private int heartbeatIntervalMs = 15_000;
    private int maxPollIntervalMs = 300_000;

    // Fetch tuning
    private int fetchMinBytes = 1_048_576;    // 1 MB
    private int fetchMaxBytes = 52_428_800;   // 50 MB
    private int fetchMaxWaitMs = 500;

    // Isolation & auto-commit interval (only used if auto-commit true)
    private boolean isolationReadCommitted = true;
    private int autoCommitIntervalMs = 5_000;

    // Network
    private int requestTimeoutMs = 30_000;
    private int retryBackoffMs = 100;
    private int reconnectBackoffMs = 1_000;

    // Identity (recommend: ${CLUSTER_ID}-${HOSTNAME})
    private String clientId = "default-client";
    private String groupInstanceId = "";

    // Extra dynamic props passthrough
    private Map<String, Object> extra = new HashMap<>();

    // ---- Getters/Setters (required for @ConfigurationProperties) ----
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

/* =====================================================================
   SPRING CONFIG (consumer factory + container)
   ===================================================================== */
@Configuration
@EnableConfigurationProperties(KafkaConsumerProperties.class)
@EnableTransactionManagement
class KafkaConsumerConfig {

    @Bean
    ConsumerFactory<String, String> consumerFactory(KafkaConsumerProperties props) {
        Map<String, Object> cfg = new HashMap<>();

        // Connection & identity
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, props.getGroupId());
        cfg.put(ConsumerConfig.CLIENT_ID_CONFIG, props.getClientId());
        if (props.getGroupInstanceId() != null && !props.getGroupInstanceId().isEmpty()) {
            cfg.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, props.getGroupInstanceId());
        }

        // Core behavior
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, props.isEnableAutoCommit());
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, props.getAutoOffsetReset());
        cfg.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, props.getMaxPollRecords());

        // Polling & heartbeats
        cfg.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, props.getMaxPollIntervalMs());
        cfg.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, props.getSessionTimeoutMs());
        cfg.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, props.getHeartbeatIntervalMs());

        // Fetch & network
        cfg.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, props.getFetchMinBytes());
        cfg.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, props.getFetchMaxBytes());
        cfg.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, props.getFetchMaxWaitMs());
        cfg.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, props.getRequestTimeoutMs());
        cfg.put(ConsumerConfig.RETRY_BACKOFF_MS_CONFIG, props.getRetryBackoffMs());
        cfg.put(ConsumerConfig.RECONNECT_BACKOFF_MS_CONFIG, props.getReconnectBackoffMs());

        // Isolation (for transactional producers)
        if (props.isIsolationReadCommitted()) {
            cfg.put("isolation.level", "read_committed");
        }

        // Auto-commit interval (only if auto-commit true)
        if (props.isEnableAutoCommit()) {
            cfg.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, props.getAutoCommitIntervalMs());
        }

        // Deserializers
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // Dynamic extras
        cfg.putAll(props.getExtra());

        return new DefaultKafkaConsumerFactory<>(cfg);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaConsumerProperties props) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(props.isBatchEnabled());
        factory.setConcurrency(props.getConcurrency());
        factory.getContainerProperties().setAckMode(
                props.isBatchEnabled()
                        ? ContainerProperties.AckMode.MANUAL          // batch → commit after full processing
                        : ContainerProperties.AckMode.MANUAL_IMMEDIATE // single → commit immediately on ack
        );
        return factory;
    }
}

/* =====================================================================
   DOMAIN SERVICE INTERFACES
   ===================================================================== */
interface PaymentService {
    void processPayment(ConsumerRecord<String, String> record) throws Exception;
}

interface ErrorLogBatchService {
    void insertSingle(ErrorRecord record);
    void insertBatch(List<ErrorRecord> records);
}

/* =====================================================================
   DUMMY IMPLEMENTATIONS (keep for local testing; replace in prod)
   ===================================================================== */
@Service
class DummyPaymentService implements PaymentService {
    private static final Logger log = LoggerFactory.getLogger(DummyPaymentService.class);
    @Override
    public void processPayment(ConsumerRecord<String, String> record) throws Exception {
        // TODO: Replace with real business logic (validation, DB, downstream)
        log.info("Processing payment: topic={}, partition={}, offset={}, key={}, value={}",
                record.topic(), record.partition(), record.offset(), record.key(), record.value());
        // Example to simulate failure: if (record.value() != null && record.value().contains("FAIL")) throw new RuntimeException("Simulated failure");
    }
}

/* =====================================================================
   JDBC IMPLEMENTATION — Error persistence with idempotency & transactions
   Target dialect: PostgreSQL (UPSERT via ON CONFLICT DO NOTHING)
   ---------------------------------------------------------------------
   TABLE DDL (example):
   CREATE TABLE IF NOT EXISTS payment_error_log (
       id BIGSERIAL PRIMARY KEY,
       topic TEXT NOT NULL,
       partition_id INT NOT NULL,
       offset BIGINT NOT NULL,
       record_key TEXT,
       value_preview TEXT,
       error_message TEXT,
       exception_type TEXT,
       source TEXT NOT NULL,
       occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
       CONSTRAINT uq_err_unique UNIQUE (topic, partition_id, offset, source)
   );
   ===================================================================== */
@Repository
class JdbcErrorLogBatchService implements ErrorLogBatchService {

    private static final Logger log = LoggerFactory.getLogger(JdbcErrorLogBatchService.class);

    private static final String INSERT_SINGLE_PG = """
        INSERT INTO payment_error_log
            (topic, partition_id, offset, record_key, value_preview, error_message, exception_type, source, occurred_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (topic, partition_id, offset, source) DO NOTHING
        """;

    private static final String INSERT_BATCH_PG = INSERT_SINGLE_PG; // same SQL; use batchUpdate

    private static final int BATCH_CHUNK_SIZE = 500; // keep DB happy & fast

    private final JdbcTemplate jdbc;

    JdbcErrorLogBatchService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Single-row insert, wrapped in a transaction for atomicity. */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void insertSingle(ErrorRecord r) {
        int updated = jdbc.update(
                INSERT_SINGLE_PG,
                r.getTopic(),
                r.getPartition(),
                r.getOffset(),
                r.getKey(),
                r.getValuePreview(),
                r.getErrorMessage(),
                r.getExceptionType(),
                r.getSource(),
                Timestamp.from(r.getOccurredAt())
        );
        if (updated == 0) {
            // Duplicate (idempotent upsert) — already present, treat as success
            log.info("Error row already exists (idempotent upsert). topic={}, partition={}, offset={}, source={}",
                    r.getTopic(), r.getPartition(), r.getOffset(), r.getSource());
        }
    }

    /** Batch insert with chunking; whole method is transactional for all-or-nothing semantics. */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void insertBatch(List<ErrorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        // Chunk the batch to bounded sizes
        for (int start = 0; start < records.size(); start += BATCH_CHUNK_SIZE) {
            final int from = start;
            final int to = Math.min(start + BATCH_CHUNK_SIZE, records.size());
            final List<ErrorRecord> chunk = records.subList(from, to);

            jdbc.batchUpdate(INSERT_BATCH_PG, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    ErrorRecord r = chunk.get(i);
                    ps.setString(1, r.getTopic());
                    ps.setInt(2, r.getPartition());
                    ps.setLong(3, r.getOffset());
                    ps.setString(4, r.getKey());
                    ps.setString(5, r.getValuePreview());
                    ps.setString(6, r.getErrorMessage());
                    ps.setString(7, r.getExceptionType());
                    ps.setString(8, r.getSource());
                    ps.setTimestamp(9, Timestamp.from(r.getOccurredAt()));
                }
                @Override
                public int getBatchSize() { return chunk.size(); }
            });
        }
    }
}

/* =====================================================================
   ERROR RECORD — immutable, full traceability (topic/partition/offset)
   ===================================================================== */
final class ErrorRecord {

    private final String topic;
    private final int partition;
    private final long offset;
    private final String key;
    private final String valuePreview;   // truncated to prevent bloating
    private final String errorMessage;   // truncated
    private final String exceptionType;
    private final String source;         // "SINGLE" | "BATCH"
    private final Instant occurredAt;

    private ErrorRecord(ConsumerRecord<String, String> rec, Exception ex, String source) {
        this.topic = rec.topic();
        this.partition = rec.partition();
        this.offset = rec.offset();
        this.key = rec.key();
        this.valuePreview = truncate(rec.value(), 2000);
        this.errorMessage = truncate(ex == null ? null : ex.getMessage(), 2000);
        this.exceptionType = ex == null ? null : ex.getClass().getSimpleName();
        this.source = source;
        this.occurredAt = Instant.now();
    }

    static ErrorRecord single(ConsumerRecord<String, String> rec, Exception ex) {
        return new ErrorRecord(rec, ex, "SINGLE");
    }

    static ErrorRecord batch(ConsumerRecord<String, String> rec, Exception ex) {
        return new ErrorRecord(rec, ex, "BATCH");
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ---- Getters (used by JDBC layer) ----
    public String getTopic() { return topic; }
    public int getPartition() { return partition; }
    public long getOffset() { return offset; }
    public String getKey() { return key; }
    public String getValuePreview() { return valuePreview; }
    public String getErrorMessage() { return errorMessage; }
    public String getExceptionType() { return exceptionType; }
    public String getSource() { return source; }
    public Instant getOccurredAt() { return occurredAt; }

    @Override
    public String toString() {
        return "ErrorRecord{" +
                "topic='" + topic + '\'' +
                ", partition=" + partition +
                ", offset=" + offset +
                ", key='" + key + '\'' +
                ", errorMessage='" + errorMessage + '\'' +
                ", exceptionType='" + exceptionType + '\'' +
                ", source='" + source + '\'' +
                ", occurredAt=" + occurredAt +
                '}';
    }
}
