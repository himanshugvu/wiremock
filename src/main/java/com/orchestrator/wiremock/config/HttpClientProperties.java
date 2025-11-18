package com.yourorg.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * =====================================================================
 *  PaymentConsumer.java  (single-file drop-in)
 *  - Single & Batch processing; batch uses Virtual Threads executor Bean
 *  - No unchecked list casts (safe runtime cast and validation)
 *  - Timed batch execution (invokeAll with timeout) -> returns reliably
 *  - Failures are persisted (idempotent, transactional) before commit
 *  - Full metadata (topic/partition/offset/key/value preview)
 * =====================================================================
 */
@Service
public class PaymentConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentConsumer.class);

    private final KafkaConsumerProperties props;
    private final PaymentService paymentService;
    private final ErrorLogBatchService errorLogBatchService;
    private final ExecutorService batchExecutor; // injected bean

    public PaymentConsumer(KafkaConsumerProperties props,
                           PaymentService paymentService,
                           ErrorLogBatchService errorLogBatchService,
                           ExecutorService batchExecutor) {
        this.props = props;
        this.paymentService = paymentService;
        this.errorLogBatchService = errorLogBatchService;
        this.batchExecutor = batchExecutor;
    }

    @KafkaListener(
            topics = "#{@kafkaConsumerProperties.topic}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(Object message, Acknowledgment ack) {
        if (message instanceof List<?> rawBatch) {
            // Validate & cast each element safely (no unchecked list cast)
            List<ConsumerRecord<String, String>> batch = safeBatchCast(rawBatch);
            handleBatch(batch, ack);
        } else if (message instanceof ConsumerRecord<?, ?> raw) {
            ConsumerRecord<String, String> rec = safeRecordCast(raw);
            handleSingle(rec, ack);
        } else {
            log.error("Unsupported payload type received by consumer: {}", message == null ? "null" : message.getClass());
        }
    }

    // ---------------------------------------------------------
    // SINGLE MESSAGE: commit only if processed OR error persisted
    // ---------------------------------------------------------
    private void handleSingle(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            paymentService.processPayment(record);
            ack.acknowledge();
        } catch (Exception processingEx) {
            log.error("Single processing failed; persisting error. topic={}, partition={}, offset={}, key={}",
                    record.topic(), record.partition(), record.offset(), record.key(), processingEx);

            ErrorRecord err = ErrorRecord.single(record, processingEx);

            try {
                errorLogBatchService.insertSingle(err); // transactional + idempotent
                log.warn("Error persisted; committing offset (offset={}).", record.offset());
                ack.acknowledge();
            } catch (Exception errorInsertEx) {
                log.error("Error persistence failed; NOT committing offset (offset={}).", record.offset(), errorInsertEx);
                throw new RuntimeException("Processing & error persistence both failed — no commit", errorInsertEx);
            }
        }
    }

    // ---------------------------------------------------------
    // BATCH: parallel process with timeout; bulk persist errors; commit end
    // ---------------------------------------------------------
    private void handleBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        if (records.isEmpty()) {
            ack.acknowledge();
            return;
        }

        // NOTE: A batch can contain records from MULTIPLE partitions — not guaranteed single partition.
        Set<Integer> partitions = uniquePartitions(records);
        log.info("Processing batch: size={}, topic={}, partitions={}", records.size(), records.get(0).topic(), partitions);

        // Collect failures (including timeouts)
        ConcurrentLinkedQueue<ErrorRecord> errorQueue = new ConcurrentLinkedQueue<>();

        // Prepare tasks (keep original order so futures index => record index)
        List<Callable<Void>> tasks = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> rec : records) {
            tasks.add(() -> {
                try {
                    paymentService.processPayment(rec);
                } catch (Exception ex) {
                    log.error("Batch record failed. topic={}, partition={}, offset={}, key={}",
                            rec.topic(), rec.partition(), rec.offset(), rec.key(), ex);
                    errorQueue.add(ErrorRecord.batch(rec, ex));
                }
                return null;
            });
        }

        // Compute a safe timeout (don't exceed max.poll.interval.ms)
        long configured = props.getBatchProcessTimeoutMs();
        long safetyCap = Math.max(1_000L, props.getMaxPollIntervalMs() - 2_000L);
        long timeoutMs = Math.min(configured, safetyCap);

        try {
            List<Future<Void>> futures = batchExecutor.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS);

            // Handle any timeouts/cancellations
            for (int i = 0; i < futures.size(); i++) {
                Future<Void> f = futures.get(i);
                if (!f.isDone()) {
                    // Cancel and record a timeout error
                    f.cancel(true);
                    ConsumerRecord<String, String> rec = records.get(i);
                    log.error("Batch task timed out after {} ms. topic={}, partition={}, offset={}",
                              timeoutMs, rec.topic(), rec.partition(), rec.offset());
                    errorQueue.add(ErrorRecord.timeout(rec, timeoutMs));
                } else {
                    try {
                        f.get(); // surface any thrown exception (already captured above)
                    } catch (ExecutionException ee) {
                        // already captured in errorQueue in the task; just log
                        log.debug("Task completed exceptionally (already captured): {}", ee.getCause() == null ? ee : ee.getCause().getMessage());
                    }
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Batch execution interrupted", ie);
        }

        // Persist all failures (if any) atomically; only then commit
        if (!errorQueue.isEmpty()) {
            List<ErrorRecord> toInsert = new ArrayList<>(errorQueue);
            try {
                errorLogBatchService.insertBatch(toInsert);  // transactional + idempotent
                log.warn("Batch error persistence succeeded; count={}", toInsert.size());
            } catch (Exception batchInsertEx) {
                log.error("Batch error persistence failed; NOT committing offsets.", batchInsertEx);
                throw new RuntimeException("Batch error persistence failed — no commit", batchInsertEx);
            }
        }

        ack.acknowledge();
        log.info("Batch committed successfully: size={}", records.size());
    }

    // ---------- helpers ----------

    /** Safe per-element cast with validation; no unchecked list cast. */
    private static ConsumerRecord<String, String> safeRecordCast(Object obj) {
        if (!(obj instanceof ConsumerRecord<?, ?> raw)) {
            throw new IllegalArgumentException("Listener expected ConsumerRecord but got: " + (obj == null ? "null" : obj.getClass()));
        }
        Object k = raw.key();
        Object v = raw.value();
        if (k != null && !(k instanceof String)) {
            throw new IllegalArgumentException("Expected String key but got: " + k.getClass());
        }
        if (v != null && !(v instanceof String)) {
            throw new IllegalArgumentException("Expected String value but got: " + v.getClass());
        }
        @SuppressWarnings("unchecked")
        ConsumerRecord<String, String> typed = (ConsumerRecord<String, String>) raw;
        return typed;
    }

    /** Validate batch contents and cast each record safely. */
    private static List<ConsumerRecord<String, String>> safeBatchCast(List<?> rawBatch) {
        List<ConsumerRecord<String, String>> out = new ArrayList<>(rawBatch.size());
        for (Object o : rawBatch) {
            out.add(safeRecordCast(o));
        }
        return out;
    }

    private static Set<Integer> uniquePartitions(List<ConsumerRecord<String, String>> records) {
        Set<Integer> parts = new HashSet<>();
        for (ConsumerRecord<String, String> r : records) parts.add(r.partition());
        return parts;
    }
}

/* =====================================================================
   CONFIGURATION PROPERTIES (centralized; override via application.yml)
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

    // Batch executor controls:
    //  - If batchMaxParallelism <= 0 => unbounded virtual threads (per-task)
    //  - Else => fixed-size virtual pool with that many threads
    private int batchMaxParallelism = 0;

    // Max time to wait for one polled batch to be processed before we time out tasks
    // (Will be capped at maxPollIntervalMs - 2s at runtime)
    private long batchProcessTimeoutMs = 240_000L; // 4 minutes by default

    // Dynamic extras
    private Map<String, Object> extra = new HashMap<>();

    // ---- Getters / Setters (required for @ConfigurationProperties) ----
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
    public int getBatchMaxParallelism() { return batchMaxParallelism; }
    public void setBatchMaxParallelism(int batchMaxParallelism) { this.batchMaxParallelism = batchMaxParallelism; }
    public long getBatchProcessTimeoutMs() { return batchProcessTimeoutMs; }
    public void setBatchProcessTimeoutMs(long batchProcessTimeoutMs) { this.batchProcessTimeoutMs = batchProcessTimeoutMs; }
    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> extra) { this.extra = extra; }
}

/* =====================================================================
   SPRING CONFIG (consumer factory + listener container + batch executor)
   ===================================================================== */
@Configuration
@EnableConfigurationProperties(KafkaConsumerProperties.class)
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
                        : ContainerProperties.AckMode.MANUAL_IMMEDIATE // single → commit per record
        );
        return factory;
    }

    /**
     * Batch executor Bean:
     *  - If batchMaxParallelism <= 0 → "unbounded" virtual threads (per task).
     *  - Else → fixed-size virtual pool capped by batchMaxParallelism and CPU.
     *
     *  The Bean's destroy method shuts it down cleanly on app stop.
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService kafkaBatchExecutor(KafkaConsumerProperties props) {
        int cap = props.getBatchMaxParallelism();
        if (cap <= 0) {
            // Best general default for mixed I/O workloads: virtual thread per task
            return Executors.newVirtualThreadPerTaskExecutor();
        } else {
            // If your processing is CPU-heavy, cap the virtual concurrency
            int cpu = Runtime.getRuntime().availableProcessors();
            int threads = Math.max(1, Math.min(cap, cpu * 8)); // sane upper bound
            return Executors.newFixedThreadPool(threads, Thread.ofVirtual().name("kafka-vt-", 0).factory());
        }
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
   DUMMY IMPLEMENTATIONS (compile/run without DB; replace in prod)
   ===================================================================== */
@Service
@ConditionalOnMissingBean(ErrorLogBatchService.class)
class DummyPaymentService implements PaymentService {
    private static final Logger log = LoggerFactory.getLogger(DummyPaymentService.class);
    @Override
    public void processPayment(ConsumerRecord<String, String> record) throws Exception {
        // TODO: Replace with real business logic
        log.info("Processing payment: topic={}, partition={}, offset={}, key={}, value={}",
                record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}

/* =====================================================================
   JDBC IMPLEMENTATION — Error persistence with idempotency & transactions
   Target dialect: PostgreSQL (UPSERT via ON CONFLICT DO NOTHING)
   ---------------------------------------------------------------------
   TABLE DDL (recommended):
   CREATE TABLE IF NOT EXISTS payment_error_log (
       id             BIGSERIAL PRIMARY KEY,
       topic          TEXT        NOT NULL,
       partition_no   INT         NOT NULL,
       record_offset  BIGINT      NOT NULL,
       msg_key        TEXT,
       msg_value      TEXT,
       error_message  TEXT,
       exception_type TEXT,
       source         VARCHAR(16) NOT NULL,
       occurred_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
       UNIQUE (topic, partition_no, record_offset)
   );
   CREATE INDEX IF NOT EXISTS idx_payment_error_log_time ON payment_error_log (occurred_at DESC);
   ===================================================================== */
@Repository
@Primary
@ConditionalOnBean(JdbcTemplate.class)
class JdbcErrorLogBatchService implements ErrorLogBatchService {

    private static final Logger log = LoggerFactory.getLogger(JdbcErrorLogBatchService.class);

    private static final String INSERT_SQL_POSTGRES =
            "INSERT INTO payment_error_log " +
            " (topic, partition_no, record_offset, msg_key, msg_value, error_message, exception_type, source, occurred_at) " +
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            " ON CONFLICT (topic, partition_no, record_offset) DO NOTHING";

    private static final int BATCH_CHUNK_SIZE = 1000; // tune per environment

    private final JdbcTemplate jdbc;

    JdbcErrorLogBatchService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Single row; idempotent (UNIQUE + DO NOTHING) and transactional. */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void insertSingle(ErrorRecord r) {
        try {
            jdbc.update(INSERT_SQL_POSTGRES,
                    r.getTopic(),
                    r.getPartition(),
                    r.getOffset(),
                    r.getKey(),
                    r.getValuePreview(),
                    r.getErrorMessage(),
                    r.getExceptionType(),
                    r.getSource(),
                    toTs(r.getOccurredAt())
            );
        } catch (DuplicateKeyException dup) {
            // Idempotency: duplicate means already logged — treat as success
            log.debug("Duplicate error row skipped (idempotent): {}:{}:{}", r.getTopic(), r.getPartition(), r.getOffset());
        } catch (DataAccessException dae) {
            log.error("insertSingle failed", dae);
            throw dae;
        }
    }

    /** Batch insert with chunking; whole method is transactional (all-or-nothing). */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void insertBatch(List<ErrorRecord> records) {
        if (records == null || records.isEmpty()) return;

        final int size = records.size();
        for (int start = 0; start < size; start += BATCH_CHUNK_SIZE) {
            final int end = Math.min(start + BATCH_CHUNK_SIZE, size);
            final List<ErrorRecord> slice = records.subList(start, end);

            jdbc.batchUpdate(INSERT_SQL_POSTGRES, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    ErrorRecord r = slice.get(i);
                    ps.setString(1, r.getTopic());
                    ps.setInt(2, r.getPartition());
                    ps.setLong(3, r.getOffset());
                    ps.setString(4, r.getKey());
                    ps.setString(5, r.getValuePreview());
                    ps.setString(6, r.getErrorMessage());
                    ps.setString(7, r.getExceptionType());
                    ps.setString(8, r.getSource());
                    ps.setTimestamp(9, toTs(r.getOccurredAt()));
                }
                @Override public int getBatchSize() { return slice.size(); }
            });
        }
    }

    private static Timestamp toTs(Instant instant) {
        return Timestamp.from(instant == null ? Instant.now() : instant);
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
    private final String source;         // "SINGLE" | "BATCH" | "TIMEOUT"
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

    static ErrorRecord timeout(ConsumerRecord<String, String> rec, long timeoutMs) {
        return new ErrorRecord(rec, new TimeoutException("Processing timed out after " + timeoutMs + " ms"), "TIMEOUT");
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ---- Getters (used by JDBC binder) ----
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
