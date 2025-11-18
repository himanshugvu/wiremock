package com.yourorg.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * =====================================================================
 *  PaymentConsumer.java  (single-file drop-in)
 *  - Consumes records and PRODUCES a Kafka message per record (async)
 *  - Commits only after send success OR (on failure) error persisted
 *  - Batch: parallel on virtual threads; wait for all sends; bulk error insert; commit at end
 *  - Strong producer settings: idempotence, acks=all, retries, compression, batching
 *  - Metrics (Micrometer): send latency, counts, DB timing, commits, timeouts
 * =====================================================================
 */
@Service
public class PaymentConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentConsumer.class);

    private final KafkaConsumerProperties consumerProps;
    private final KafkaProducerProperties producerProps;
    private final PaymentService paymentService;               // now produces to Kafka
    private final ErrorLogBatchService errorLogBatchService;   // DB error store (idempotent)
    private final ExecutorService batchExecutor;               // virtual-thread executor bean
    private final MeterRegistry metrics;

    public PaymentConsumer(KafkaConsumerProperties consumerProps,
                           KafkaProducerProperties producerProps,
                           PaymentService paymentService,
                           ErrorLogBatchService errorLogBatchService,
                           ExecutorService batchExecutor,
                           MeterRegistry metrics) {
        this.consumerProps = consumerProps;
        this.producerProps = producerProps;
        this.paymentService = paymentService;
        this.errorLogBatchService = errorLogBatchService;
        this.batchExecutor = batchExecutor;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = "#{@kafkaConsumerProperties.topic}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(Object message, Acknowledgment ack) {
        if (message instanceof List<?> rawBatch) {
            List<ConsumerRecord<String, String>> batch = safeBatchCast(rawBatch);
            handleBatch(batch, ack);
        } else if (message instanceof ConsumerRecord<?, ?> raw) {
            ConsumerRecord<String, String> rec = safeRecordCast(raw);
            handleSingle(rec, ack);
        } else {
            log.error("Unsupported payload type received by consumer: {}", message == null ? "null" : message.getClass());
        }
    }

    // ------------------------------------------------------------------
    // SINGLE: produce async; ack only after send success OR error persisted
    //  (No DB logic inside catch; we capture failure and act afterwards.)
    // ------------------------------------------------------------------
    private void handleSingle(ConsumerRecord<String, String> record, Acknowledgment ack) {
        Timer.Sample sample = Timer.start(metrics);

        // Start async produce for this record (may be 1..N sends; combined future completes when all done)
        CompletableFuture<Void> sendFuture = paymentService.processPayment(record);

        // Wait bounded time for async send (don’t exceed poll interval)
        long timeoutMs = Math.min(
                producerProps.getSendTimeoutMs(),
                Math.max(1_000L, consumerProps.getMaxPollIntervalMs() - 2_000L)
        );

        Throwable failure = await(sendFuture, timeoutMs);

        String resultTag;
        if (failure == null) {
            ack.acknowledge();
            resultTag = "sent";
            metrics.counter("kafka.consumer.commits", "mode", "single", "topic", record.topic()).increment();
        } else {
            // Persist error row; commit only if persistence succeeded
            log.error("Single produce failed; attempting error persistence. topic={}, partition={}, offset={}, key={}",
                    record.topic(), record.partition(), record.offset(), record.key(), failure);

            ErrorRecord err = ErrorRecord.single(record, failure);
            boolean persisted = persistSingleError(err);

            if (persisted) {
                ack.acknowledge();
                resultTag = "error_logged";
                metrics.counter("kafka.consumer.commits", "mode", "single", "topic", record.topic()).increment();
            } else {
                resultTag = "error_persist_failed";
                sample.stop(Timer.builder("kafka.consumer.process.latency")
                        .tag("mode", "single").tag("topic", record.topic()).tag("result", resultTag)
                        .register(metrics));
                throw new RuntimeException("Produce failed and error persistence failed — no commit", failure);
            }
        }

        // Metrics: end-to-end latency for single path
        sample.stop(Timer.builder("kafka.consumer.process.latency")
                .tag("mode", "single").tag("topic", record.topic()).tag("result", resultTag)
                .register(metrics));
        metrics.counter("kafka.consumer.messages", "mode", "single", "topic", record.topic(), "result", resultTag).increment();
    }

    // ------------------------------------------------------------------
    // BATCH: parallel async produce; wait with timeout; bulk error insert; commit end
    // ------------------------------------------------------------------
    private void handleBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        if (records.isEmpty()) {
            ack.acknowledge();
            return;
        }

        final String topic = records.get(0).topic();
        final Set<Integer> partitions = records.stream().map(ConsumerRecord::partition).collect(Collectors.toSet());
        log.info("Processing batch: size={}, topic={}, partitions={}", records.size(), topic, partitions);

        Timer.Sample batchSample = Timer.start(metrics);
        ConcurrentLinkedQueue<ErrorRecord> errors = new ConcurrentLinkedQueue<>();

        // Build callable tasks: each task blocks (on a virtual thread) for its record’s async send to complete
        List<Callable<Void>> tasks = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> rec : records) {
            tasks.add(() -> {
                Timer.Sample msgSample = Timer.start(metrics);
                String res = "sent";
                try {
                    CompletableFuture<Void> sendFut = paymentService.processPayment(rec);
                    long timeoutMs = Math.min(
                            producerProps.getSendTimeoutMs(),
                            Math.max(1_000L, consumerProps.getMaxPollIntervalMs() - 2_000L)
                    );
                    Throwable f = await(sendFut, timeoutMs);
                    if (f != null) {
                        res = "failed";
                        errors.add(ErrorRecord.batch(rec, f));
                    }
                } finally {
                    msgSample.stop(Timer.builder("kafka.consumer.process.latency")
                            .tag("mode", "batch").tag("topic", rec.topic()).tag("result", res)
                            .register(metrics));
                    metrics.counter("kafka.consumer.messages", "mode", "batch", "topic", rec.topic(), "result", res).increment();
                }
                return null;
            });
        }

        // Global cap so we never exceed poll interval
        long globalTimeoutMs = Math.min(
                consumerProps.getBatchProcessTimeoutMs(),
                Math.max(1_000L, consumerProps.getMaxPollIntervalMs() - 2_000L)
        );

        try {
            List<Future<Void>> futures = batchExecutor.invokeAll(tasks, globalTimeoutMs, TimeUnit.MILLISECONDS);

            // Any not-done → treat as timeout failure
            int timeouts = 0;
            for (int i = 0; i < futures.size(); i++) {
                Future<Void> f = futures.get(i);
                if (!f.isDone()) {
                    f.cancel(true);
                    ConsumerRecord<String, String> rec = records.get(i);
                    errors.add(ErrorRecord.timeout(rec, globalTimeoutMs));
                    timeouts++;
                }
            }
            if (timeouts > 0) {
                metrics.counter("kafka.consumer.timeouts", "topic", topic).increment(timeouts);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            metrics.counter("kafka.consumer.timeouts", "topic", topic).increment(records.size());
            throw new RuntimeException("Batch execution interrupted — offsets not committed", ie);
        }

        // Persist failures (if any) in one TX; only then commit
        String batchResult = "committed";
        if (!errors.isEmpty()) {
            List<ErrorRecord> toInsert = new ArrayList<>(errors);
            try {
                errorLogBatchService.insertBatch(toInsert);
                metrics.counter("kafka.consumer.errorlogged", "mode", "batch", "topic", topic).increment(toInsert.size());
            } catch (Exception dbEx) {
                batchResult = "db_error";
                batchSample.stop(Timer.builder("kafka.consumer.batch.latency")
                        .tag("topic", topic).tag("result", batchResult).tag("size", Integer.toString(records.size()))
                        .register(metrics));
                throw new RuntimeException("Batch error persistence failed — offsets not committed", dbEx);
            }
        }

        ack.acknowledge();
        metrics.counter("kafka.consumer.commits", "mode", "batch", "topic", topic).increment();
        batchSample.stop(Timer.builder("kafka.consumer.batch.latency")
                .tag("topic", topic).tag("result", batchResult).tag("size", Integer.toString(records.size()))
                .register(metrics));
        log.info("Batch committed successfully: size={}", records.size());
    }

    // ---------- helpers ----------

    /** Wait for a CompletionStage up to timeout; return null on success, otherwise the failure (TimeoutException on timeouts). */
    private static Throwable await(CompletionStage<?> stage, long timeoutMs) {
        try {
            stage.toCompletableFuture().get(timeoutMs, TimeUnit.MILLISECONDS);
            return null;
        } catch (TimeoutException te) {
            return te;
        } catch (ExecutionException ee) {
            return ee.getCause() == null ? ee : ee.getCause();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ie;
        }
    }

    /** Persist a single error row; return true on success (idempotent), false on failure. */
    private boolean persistSingleError(ErrorRecord row) {
        try {
            errorLogBatchService.insertSingle(row);
            return true;
        } catch (Exception dbEx) {
            log.error("Persisting single error row failed. topic={}, partition={}, offset={}",
                    row.getTopic(), row.getPartition(), row.getOffset(), dbEx);
            return false;
        }
    }

    /** Safe per-element cast with validation; no unchecked list cast. */
    private static ConsumerRecord<String, String> safeRecordCast(Object obj) {
        if (!(obj instanceof ConsumerRecord<?, ?> raw)) {
            throw new IllegalArgumentException("Listener expected ConsumerRecord but got: " + (obj == null ? "null" : obj.getClass()));
        }
        if (raw.key() != null && !(raw.key() instanceof String)) {
            throw new IllegalArgumentException("Expected String key but got: " + raw.key().getClass());
        }
        if (raw.value() != null && !(raw.value() instanceof String)) {
            throw new IllegalArgumentException("Expected String value but got: " + raw.value().getClass());
        }
        @SuppressWarnings("unchecked") ConsumerRecord<String, String> typed = (ConsumerRecord<String, String>) raw;
        return typed;
    }

    /** Validate batch contents and cast each record safely. */
    private static List<ConsumerRecord<String, String>> safeBatchCast(List<?> rawBatch) {
        List<ConsumerRecord<String, String>> out = new ArrayList<>(rawBatch.size());
        for (Object o : rawBatch) out.add(safeRecordCast(o));
        return out;
    }
}

/* =====================================================================
   CONFIGURATION PROPERTIES — Consumer
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

    // Isolation & auto-commit interval (only when auto-commit true)
    private boolean isolationReadCommitted = true;
    private int autoCommitIntervalMs = 5_000;

    // Network
    private int requestTimeoutMs = 30_000;
    private int retryBackoffMs = 100;
    private int reconnectBackoffMs = 1_000;

    // Identity (recommend: ${CLUSTER_ID}-${HOSTNAME})
    private String clientId = "default-client";
    private String groupInstanceId = "";

    // Batch time budget (will be capped by poll interval - 2s)
    private long batchProcessTimeoutMs = 240_000L;

    // Dynamic extras
    private Map<String, Object> extra = new HashMap<>();

    // getters/setters (boilerplate)
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
    public long getBatchProcessTimeoutMs() { return batchProcessTimeoutMs; }
    public void setBatchProcessTimeoutMs(long batchProcessTimeoutMs) { this.batchProcessTimeoutMs = batchProcessTimeoutMs; }
    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> extra) { this.extra = extra; }
}

/* =====================================================================
   CONFIGURATION PROPERTIES — Producer
   ===================================================================== */
@ConfigurationProperties(prefix = "kafka.producer")
class KafkaProducerProperties {

    // Where to produce (default: reuse consumer bootstrap)
    private String bootstrapServers;
    private String targetTopic = "payments-processed";

    // Delivery semantics
    private String acks = "all";
    private boolean idempotence = true;
    private int retries = Integer.MAX_VALUE;
    private int maxInFlightRequestsPerConnection = 5; // safe with idempotence

    // Batching & compression for throughput
    private int batchSizeBytes = 32 * 1024;   // 32 KiB
    private int lingerMs = 10;
    private String compressionType = "lz4";   // or "zstd" if cluster supports

    // Timeouts & buffers
    private int requestTimeoutMs = 30_000;
    private int deliveryTimeoutMs = 120_000;
    private long bufferMemoryBytes = 64L * 1024 * 1024; // 64 MiB

    // Async send await timeout (per record)
    private long sendTimeoutMs = 60_000L;

    // Header behavior
    private boolean copyHeaders = true;

    // getters/setters
    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }
    public String getTargetTopic() { return targetTopic; }
    public void setTargetTopic(String targetTopic) { this.targetTopic = targetTopic; }
    public String getAcks() { return acks; }
    public void setAcks(String acks) { this.acks = acks; }
    public boolean isIdempotence() { return idempotence; }
    public void setIdempotence(boolean idempotence) { this.idempotence = idempotence; }
    public int getRetries() { return retries; }
    public void setRetries(int retries) { this.retries = retries; }
    public int getMaxInFlightRequestsPerConnection() { return maxInFlightRequestsPerConnection; }
    public void setMaxInFlightRequestsPerConnection(int v) { this.maxInFlightRequestsPerConnection = v; }
    public int getBatchSizeBytes() { return batchSizeBytes; }
    public void setBatchSizeBytes(int batchSizeBytes) { this.batchSizeBytes = batchSizeBytes; }
    public int getLingerMs() { return lingerMs; }
    public void setLingerMs(int lingerMs) { this.lingerMs = lingerMs; }
    public String getCompressionType() { return compressionType; }
    public void setCompressionType(String compressionType) { this.compressionType = compressionType; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
    public int getDeliveryTimeoutMs() { return deliveryTimeoutMs; }
    public void setDeliveryTimeoutMs(int deliveryTimeoutMs) { this.deliveryTimeoutMs = deliveryTimeoutMs; }
    public long getBufferMemoryBytes() { return bufferMemoryBytes; }
    public void setBufferMemoryBytes(long bufferMemoryBytes) { this.bufferMemoryBytes = bufferMemoryBytes; }
    public long getSendTimeoutMs() { return sendTimeoutMs; }
    public void setSendTimeoutMs(long sendTimeoutMs) { this.sendTimeoutMs = sendTimeoutMs; }
    public boolean isCopyHeaders() { return copyHeaders; }
    public void setCopyHeaders(boolean copyHeaders) { this.copyHeaders = copyHeaders; }
}

/* =====================================================================
   SPRING CONFIG — Consumer, Producer, Executor, Metrics
   ===================================================================== */
@Configuration
@EnableConfigurationProperties({KafkaConsumerProperties.class, KafkaProducerProperties.class})
class KafkaConfig {

    // ---------- Consumer beans ----------
    @Bean
    ConsumerFactory<String, String> consumerFactory(KafkaConsumerProperties props) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, props.getGroupId());
        cfg.put(ConsumerConfig.CLIENT_ID_CONFIG, props.getClientId());
        if (props.getGroupInstanceId() != null && !props.getGroupInstanceId().isEmpty()) {
            cfg.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, props.getGroupInstanceId());
        }
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, props.isEnableAutoCommit());
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, props.getAutoOffsetReset());
        cfg.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, props.getMaxPollRecords());
        cfg.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, props.getMaxPollIntervalMs());
        cfg.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, props.getSessionTimeoutMs());
        cfg.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, props.getHeartbeatIntervalMs());
        cfg.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, props.getFetchMinBytes());
        cfg.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, props.getFetchMaxBytes());
        cfg.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, props.getFetchMaxWaitMs());
        cfg.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, props.getRequestTimeoutMs());
        cfg.put(ConsumerConfig.RETRY_BACKOFF_MS_CONFIG, props.getRetryBackoffMs());
        cfg.put(ConsumerConfig.RECONNECT_BACKOFF_MS_CONFIG, props.getReconnectBackoffMs());
        if (props.isIsolationReadCommitted()) cfg.put("isolation.level", "read_committed");
        if (props.isEnableAutoCommit()) cfg.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, props.getAutoCommitIntervalMs());
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.putAll(props.getExtra());
        return new DefaultKafkaConsumerFactory<>(cfg);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaConsumerProperties props) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(props.isBatchEnabled());
        factory.setConcurrency(props.getConcurrency());
        factory.getContainerProperties().setAckMode(
                props.isBatchEnabled()
                        ? ContainerProperties.AckMode.MANUAL
                        : ContainerProperties.AckMode.MANUAL_IMMEDIATE
        );
        return factory;
    }

    // ---------- Producer beans ----------
    @Bean
    ProducerFactory<String, String> producerFactory(KafkaConsumerProperties consumerProps,
                                                    KafkaProducerProperties producerProps) {
        Map<String, Object> cfg = new HashMap<>();
        // Prefer producer bootstrap if set; else reuse consumer
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                producerProps.getBootstrapServers() != null ? producerProps.getBootstrapServers() : consumerProps.getBootstrapServers());

        // Best practices for safety + throughput
        cfg.put(ProducerConfig.ACKS_CONFIG, producerProps.getAcks());                            // all
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, producerProps.isIdempotence());       // true
        cfg.put(ProducerConfig.RETRIES_CONFIG, producerProps.getRetries());                     // lots of retries
        cfg.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, producerProps.getMaxInFlightRequestsPerConnection()); // <=5 with idempotence
        cfg.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, producerProps.getCompressionType());    // lz4/zstd
        cfg.put(ProducerConfig.BATCH_SIZE_CONFIG, producerProps.getBatchSizeBytes());           // 32 KiB
        cfg.put(ProducerConfig.LINGER_MS_CONFIG, producerProps.getLingerMs());                  // small linger to batch
        cfg.put(ProducerConfig.BUFFER_MEMORY_CONFIG, producerProps.getBufferMemoryBytes());     // 64 MiB
        cfg.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, producerProps.getRequestTimeoutMs());
        cfg.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, producerProps.getDeliveryTimeoutMs());

        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(cfg);
    }

    @Bean
    KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(pf);
        // Optional: observe per-batch success/failure with interceptors if needed
        return template;
    }

    // ---------- Executor & Metrics ----------
    @Bean(destroyMethod = "shutdown")
    ExecutorService kafkaBatchExecutor() {
        // Best default: virtual-thread per task (I/O bound, many small async sends)
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    MeterRegistry meterRegistryFallback() {
        return new SimpleMeterRegistry();
    }
}

/* =====================================================================
   DOMAIN SERVICE — does the PRODUCE for each record (async Kafka send)
   ===================================================================== */
interface PaymentService {
    /**
     * Process one consumed record:
     * - Transform/copy headers (optional)
     * - Produce to the target topic asynchronously
     * - Return a future that completes when ALL sends for this record complete
     *   (here it's one send, but design supports 1..N).
     */
    CompletableFuture<Void> processPayment(ConsumerRecord<String, String> record);
}

/**
 * Default implementation: one async send per consumed record, copying headers (optional).
 * We return a CompletableFuture&lt;Void&gt; that fails if the send fails.
 */
@Service
@ConditionalOnBean(KafkaTemplate.class)
class KafkaProducingPaymentService implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducingPaymentService.class);

    private final KafkaTemplate<String, String> template;
    private final KafkaProducerProperties props;
    private final MeterRegistry metrics;

    KafkaProducingPaymentService(KafkaTemplate<String, String> template,
                                 KafkaProducerProperties props,
                                 MeterRegistry metrics) {
        this.template = template;
        this.props = props;
        this.metrics = metrics;
    }

    @Override
    public CompletableFuture<Void> processPayment(ConsumerRecord<String, String> record) {
        // Build ProducerRecord so we can copy headers and set key/value explicitly
        ProducerRecord<String, String> out = new ProducerRecord<>(props.getTargetTopic(), record.key(), record.value());
        // Recommended: carry traceability (source topic/partition/offset)
        out.headers().add("src-topic", record.topic().getBytes(StandardCharsets.UTF_8));
        out.headers().add("src-partition", Integer.toString(record.partition()).getBytes(StandardCharsets.UTF_8));
        out.headers().add("src-offset", Long.toString(record.offset()).getBytes(StandardCharsets.UTF_8));
        if (props.isCopyHeaders() && record.headers() != null) {
            // Copy existing headers (avoid overwriting the src-* we just set)
            for (Header h : record.headers()) {
                if (!h.key().startsWith("src-")) {
                    out.headers().add(h);
                }
            }
        }

        // Send asynchronously and wrap with timing + result tags
        Timer.Sample sample = Timer.start(metrics);
        CompletableFuture<SendResult<String, String>> send = template.send(out);

        return send.handle((res, err) -> {
            String result = (err == null) ? "success" : "failed";
            sample.stop(Timer.builder("kafka.producer.send.latency")
                    .tag("topic", props.getTargetTopic())
                    .tag("result", result)
                    .register(metrics));
            metrics.counter("kafka.producer.send.count", "topic", props.getTargetTopic(), "result", result).increment();

            if (err != null) {
                // Propagate the error so the consumer can decide commit policy
                throw (err instanceof CompletionException) ? (CompletionException) err : new CompletionException(err);
            }
            return null; // map to Void
        }).toCompletableFuture();
    }
}

/* =====================================================================
   FALLBACK PaymentService (only if no KafkaTemplate bean exists)
   ===================================================================== */
@Service
@ConditionalOnMissingBean(PaymentService.class)
class DummyPaymentService implements PaymentService {
    private static final Logger log = LoggerFactory.getLogger(DummyPaymentService.class);
    @Override public CompletableFuture<Void> processPayment(ConsumerRecord<String, String> record) {
        log.info("Dummy process (no KafkaTemplate): key={}, value={}", record.key(), record.value());
        return CompletableFuture.completedFuture(null);
    }
}

/* =====================================================================
   ERROR STORE — JDBC (PostgreSQL), TRANSACTIONAL + IDEMPOTENT
   ===================================================================== */
interface ErrorLogBatchService {
    void insertSingle(ErrorRecord record);
    void insertBatch(List<ErrorRecord> records);
}

/**
 * PostgreSQL implementation using INSERT ... ON CONFLICT DO NOTHING
 * - Single & batch operations are transactional
 * - Duplicate errors for the same (topic, partition, offset) are ignored (idempotency)
 */
@Repository
@ConditionalOnBean(JdbcTemplate.class)
class JdbcErrorLogBatchService implements ErrorLogBatchService {

    private static final Logger log = LoggerFactory.getLogger(JdbcErrorLogBatchService.class);

    private static final String INSERT_SQL = """
        INSERT INTO payment_error_log
            (topic, partition_no, record_offset, msg_key, msg_value,
             error_message, exception_type, source, occurred_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (topic, partition_no, record_offset) DO NOTHING
        """;

    private static final int BATCH_CHUNK_SIZE = 1000;

    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;

    JdbcErrorLogBatchService(JdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void insertSingle(ErrorRecord r) {
        Timer.Sample sample = Timer.start(metrics);
        String result = "ok";
        try {
            int updated = jdbc.update(INSERT_SQL,
                    r.getTopic(), r.getPartition(), r.getOffset(),
                    r.getKey(), r.getValuePreview(),
                    r.getErrorMessage(), r.getExceptionType(), r.getSource(),
                    Timestamp.from(r.getOccurredAt()));
            if (updated == 0) {
                result = "duplicate";
                log.debug("Duplicate error row ignored: {}:{}:{}", r.getTopic(), r.getPartition(), r.getOffset());
            }
        } catch (DuplicateKeyException dup) {
            result = "duplicate";
            log.debug("Duplicate error row ignored: {}:{}:{}", r.getTopic(), r.getPartition(), r.getOffset());
        } catch (DataAccessException dae) {
            result = "failed";
            log.error("insertSingle failed", dae);
            throw dae;
        } finally {
            sample.stop(Timer.builder("kafka.consumer.errorlog.single.latency")
                    .tag("topic", r.getTopic()).tag("result", result).register(metrics));
            metrics.counter("kafka.consumer.errorlog.single.count", "topic", r.getTopic(), "result", result).increment();
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void insertBatch(List<ErrorRecord> records) {
        if (records == null || records.isEmpty()) return;

        Timer.Sample total = Timer.start(metrics);
        int totalCount = records.size();
        try {
            for (int start = 0; start < records.size(); start += BATCH_CHUNK_SIZE) {
                List<ErrorRecord> slice = records.subList(start, Math.min(start + BATCH_CHUNK_SIZE, records.size()));
                Timer.Sample chunk = Timer.start(metrics);
                jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
                    @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ErrorRecord r = slice.get(i);
                        int idx = 1;
                        ps.setString(idx++, r.getTopic());
                        ps.setInt(idx++, r.getPartition());
                        ps.setLong(idx++, r.getOffset());
                        ps.setString(idx++, r.getKey());
                        ps.setString(idx++, r.getValuePreview());
                        ps.setString(idx++, r.getErrorMessage());
                        ps.setString(idx++, r.getExceptionType());
                        ps.setString(idx++, r.getSource());
                        ps.setTimestamp(idx++, Timestamp.from(r.getOccurredAt()));
                    }
                    @Override public int getBatchSize() { return slice.size(); }
                });
                chunk.stop(Timer.builder("kafka.consumer.errorlog.batch.chunk.latency")
                        .tag("size", Integer.toString(slice.size())).register(metrics));
            }
        } finally {
            total.stop(Timer.builder("kafka.consumer.errorlog.batch.total.latency")
                    .tag("size", Integer.toString(totalCount)).register(metrics));
            metrics.counter("kafka.consumer.errorlog.batch.count", "result", "ok").increment(totalCount);
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
    private final String source;         // "SINGLE" | "BATCH" | "TIMEOUT"
    private final Instant occurredAt;

    private ErrorRecord(ConsumerRecord<String, String> rec, Throwable ex, String source) {
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

    static ErrorRecord single(ConsumerRecord<String, String> rec, Throwable ex) { return new ErrorRecord(rec, ex, "SINGLE"); }
    static ErrorRecord batch(ConsumerRecord<String, String> rec, Throwable ex)  { return new ErrorRecord(rec, ex, "BATCH"); }
    static ErrorRecord timeout(ConsumerRecord<String, String> rec, long timeoutMs) {
        return new ErrorRecord(rec, new TimeoutException("Processing timed out after " + timeoutMs + " ms"), "TIMEOUT");
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // getters (used by JDBC binder)
    public String getTopic() { return topic; }
    public int getPartition() { return partition; }
    public long getOffset() { return offset; }
    public String getKey() { return key; }
    public String getValuePreview() { return valuePreview; }
    public String getErrorMessage() { return errorMessage; }
    public String getExceptionType() { return exceptionType; }
    public String getSource() { return source; }
    public Instant getOccurredAt() { return occurredAt; }

    @Override public String toString() {
        return "ErrorRecord{" +
                "topic='" + topic + '\'' +
                ", partition=" + partition +
                ", offset=" + offset +
                ", key='" + key + '\'' +
                ", exceptionType='" + exceptionType + '\'' +
                ", source='" + source + '\'' +
                ", occurredAt=" + occurredAt +
                '}';
    }
}
