package com.yourorg.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.RetriableException;
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
import org.springframework.util.concurrent.ListenableFuture;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * =====================================================================
 *  PaymentConsumer.java  (single-file drop-in)
 *  - Consume -> async PRODUCE per record (returns ProduceOutcome)
 *  - Hybrid timeouts: per-record AND global batch cap
 *  - Commit only after send success OR (on failure) after error persisted
 *  - Batch: virtual-thread fan-out; wait bounded; bulk error insert; commit
 *  - Producer: idempotence, acks=all, retries, backoff, batching, compression
 *  - Metrics (Micrometer): send/consume latency, counts, DB timing, timeouts
 * =====================================================================
 */
@Service
public class PaymentConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentConsumer.class);

    private final KafkaConsumerProperties consumerProps;
    private final KafkaProducerProperties producerProps;
    private final PaymentService paymentService;               // produces to Kafka and returns ProduceOutcome
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
    // ------------------------------------------------------------------
    private void handleSingle(ConsumerRecord<String, String> record, Acknowledgment ack) {
        Timer.Sample sample = Timer.start(metrics);

        CompletableFuture<ProduceOutcome> future = paymentService.processPayment(record);

        long perRecordBudget = Math.min(
                producerProps.getSendTimeoutMs(),
                Math.max(1_000L, consumerProps.getMaxPollIntervalMs() - 2_000L)
        );

        ProduceOutcome result;
        String outcomeTag;
        try {
            result = future.get(perRecordBudget, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            result = ProduceOutcome.failure(producerProps.getTargetTopic(), te, true);
        } catch (ExecutionException ee) {
            result = ProduceOutcome.failure(producerProps.getTargetTopic(), rootCause(ee), false);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            result = ProduceOutcome.failure(producerProps.getTargetTopic(), ie, true);
        }

        if (result.isSuccess()) {
            ack.acknowledge();
            outcomeTag = "sent";
            metrics.counter("kafka.consumer.commits", "mode", "single", "topic", record.topic()).increment();
        } else {
            log.error("Single produce failed; attempting error persistence. topic={}, partition={}, offset={}, key={}, errType={}, retriable={}",
                    record.topic(), record.partition(), record.offset(), record.key(),
                    result.getExceptionType(), result.isRetriable());

            ErrorRecord err = ErrorRecord.fromOutcome(record, result, "SINGLE");
            boolean persisted = persistSingleError(err);

            if (persisted) {
                ack.acknowledge();
                outcomeTag = "error_logged";
                metrics.counter("kafka.consumer.commits", "mode", "single", "topic", record.topic()).increment();
            } else {
                outcomeTag = "error_persist_failed";
                sample.stop(Timer.builder("kafka.consumer.process.latency")
                        .tag("mode", "single").tag("topic", record.topic()).tag("result", outcomeTag)
                        .register(metrics));
                throw new RuntimeException("Produce failed and error persistence failed — no commit: " + result.getErrorMessage());
            }
        }

        sample.stop(Timer.builder("kafka.consumer.process.latency")
                .tag("mode", "single").tag("topic", record.topic()).tag("result", outcomeTag)
                .register(metrics));
        metrics.counter("kafka.consumer.messages", "mode", "single", "topic", record.topic(), "result", outcomeTag).increment();
    }

    // ------------------------------------------------------------------
    // BATCH: parallel async produce; per-record timeout; global cap; bulk error insert; commit end
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

        // Global cap (safety net) so we never exceed poll interval
        final long globalTimeoutMs = Math.min(
                consumerProps.getBatchProcessTimeoutMs(),
                Math.max(1_000L, consumerProps.getMaxPollIntervalMs() - 2_000L)
        );

        // Per-record budget (fail fast for stragglers) AND never exceed the global cap
        long perRecordTimeoutMs = Math.min(
                producerProps.getSendTimeoutMs(),
                globalTimeoutMs > 1_000L ? globalTimeoutMs - 500L : globalTimeoutMs
        );
        perRecordTimeoutMs = Math.max(500L, perRecordTimeoutMs); // floor

        List<Callable<Void>> tasks = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> rec : records) {
            tasks.add(() -> {
                Timer.Sample msgSample = Timer.start(metrics);
                String res = "sent";
                try {
                    CompletableFuture<ProduceOutcome> f = paymentService.processPayment(rec);

                    ProduceOutcome pr;
                    try {
                        pr = f.get(perRecordTimeoutMs, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException te) {
                        pr = ProduceOutcome.failure(producerProps.getTargetTopic(), te, true);
                    } catch (ExecutionException ee) {
                        pr = ProduceOutcome.failure(producerProps.getTargetTopic(), rootCause(ee), false);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        pr = ProduceOutcome.failure(producerProps.getTargetTopic(), ie, true);
                    }

                    if (!pr.isSuccess()) {
                        res = "failed";
                        errors.add(ErrorRecord.fromOutcome(rec, pr, "BATCH"));
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

        try {
            List<Future<Void>> futures = batchExecutor.invokeAll(tasks, globalTimeoutMs, TimeUnit.MILLISECONDS);

            // Any not-done → treat as timeout failure (create synthetic failure outcome)
            int timeouts = 0;
            for (int i = 0; i < futures.size(); i++) {
                Future<Void> f = futures.get(i);
                if (!f.isDone()) {
                    f.cancel(true); // interrupts the virtual thread
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

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur;
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

    // Identity
    private String clientId = "default-client";
    private String groupInstanceId = "";

    // Batch time budget (capped by poll interval - 2s)
    private long batchProcessTimeoutMs = 240_000L;

    // Dynamic extras
    private Map<String, Object> extra = new HashMap<>();

    // getters/setters omitted for brevity (include in your code)
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

    private String bootstrapServers;
    private String targetTopic = "payments-processed";

    // Delivery semantics
    private String acks = "all";
    private boolean idempotence = true;
    private int retries = Integer.MAX_VALUE;
    private int maxInFlightRequestsPerConnection = 5;
    private int retryBackoffMs = 100;

    // Batching & compression
    private int batchSizeBytes = 32 * 1024;
    private int lingerMs = 10;
    private String compressionType = "lz4";

    // Timeouts & buffers
    private int requestTimeoutMs = 30_000;
    private int deliveryTimeoutMs = 120_000;
    private long bufferMemoryBytes = 64L * 1024 * 1024;

    // Await timeout (per record)
    private long sendTimeoutMs = 60_000L;

    // Header behavior
    private boolean copyHeaders = true;

    // getters/setters omitted for brevity (include in your code)
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
    public int getRetryBackoffMs() { return retryBackoffMs; }
    public void setRetryBackoffMs(int retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }
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
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                producerProps.getBootstrapServers() != null ? producerProps.getBootstrapServers() : consumerProps.getBootstrapServers());
        cfg.put(ProducerConfig.ACKS_CONFIG, producerProps.getAcks());
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, producerProps.isIdempotence());
        cfg.put(ProducerConfig.RETRIES_CONFIG, producerProps.getRetries());
        cfg.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, producerProps.getRetryBackoffMs());
        cfg.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, producerProps.getMaxInFlightRequestsPerConnection());
        cfg.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, producerProps.getCompressionType());
        cfg.put(ProducerConfig.BATCH_SIZE_CONFIG, producerProps.getBatchSizeBytes());
        cfg.put(ProducerConfig.LINGER_MS_CONFIG, producerProps.getLingerMs());
        cfg.put(ProducerConfig.BUFFER_MEMORY_CONFIG, producerProps.getBufferMemoryBytes());
        cfg.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, producerProps.getRequestTimeoutMs());
        cfg.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, producerProps.getDeliveryTimeoutMs());
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(cfg);
    }

    @Bean
    KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }

    // ---------- Executor & Metrics ----------
    @Bean(destroyMethod = "shutdown")
    ExecutorService kafkaBatchExecutor() {
        // Virtual thread per task — cheap, ideal for I/O-bound waits
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    MeterRegistry meterRegistryFallback() {
        return new SimpleMeterRegistry();
    }
}

/* =====================================================================
   PRODUCE OUTCOME — success metadata or failure details
   ===================================================================== */
final class ProduceOutcome {
    private final boolean success;
    private final String topic;
    private final Integer partition;
    private final Long offset;
    private final Long timestamp;
    private final String exceptionType;
    private final String errorMessage;
    private final String exceptionStack;
    private final boolean retriable;

    private ProduceOutcome(boolean success,
                           String topic,
                           Integer partition,
                           Long offset,
                           Long timestamp,
                           String exceptionType,
                           String errorMessage,
                           String exceptionStack,
                           boolean retriable) {
        this.success = success;
        this.topic = topic;
        this.partition = partition;
        this.offset = offset;
        this.timestamp = timestamp;
        this.exceptionType = exceptionType;
        this.errorMessage = errorMessage;
        this.exceptionStack = exceptionStack;
        this.retriable = retriable;
    }

    static ProduceOutcome success(String topic, int partition, long offset, long timestamp) {
        return new ProduceOutcome(true, topic, partition, offset, timestamp, null, null, null, false);
    }

    static ProduceOutcome success(RecordMetadata md) {
        return success(md.topic(), md.partition(), md.offset(), md.timestamp());
    }

    static ProduceOutcome failure(String topic, Throwable t, boolean maybeTransient) {
        Throwable root = (t == null) ? null : rootCause(t);
        boolean retriable = isKafkaRetriable(root) || maybeTransient;
        return new ProduceOutcome(false, topic, null, null, null,
                root == null ? null : root.getClass().getSimpleName(),
                safeMessage(root),
                stackTraceOf(root),
                retriable);
    }

    static boolean isKafkaRetriable(Throwable t) {
        while (t != null) {
            if (t instanceof RetriableException) return true;
            t = t.getCause();
        }
        return false;
    }

    static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur != null && cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur;
    }

    static String safeMessage(Throwable t) {
        if (t == null) return null;
        String m = t.getMessage();
        if (m == null) return t.getClass().getName();
        return m.length() <= 2000 ? m : m.substring(0, 2000) + "...";
    }

    static String stackTraceOf(Throwable t) {
        if (t == null) return null;
        StringWriter sw = new StringWriter(8192);
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() <= 16000 ? s : s.substring(0, 16000) + "...";
    }

    // getters
    public boolean isSuccess() { return success; }
    public String getTopic() { return topic; }
    public Integer getPartition() { return partition; }
    public Long getOffset() { return offset; }
    public Long getTimestamp() { return timestamp; }
    public String getExceptionType() { return exceptionType; }
    public String getErrorMessage() { return errorMessage; }
    public String getExceptionStack() { return exceptionStack; }
    public boolean isRetriable() { return retriable; }

    @Override public String toString() {
        return success
                ? ("ProduceOutcome{success, topic=" + topic + ", partition=" + partition + ", offset=" + offset + ", ts=" + timestamp + "}")
                : ("ProduceOutcome{failure, topic=" + topic + ", error=" + exceptionType + ": " + errorMessage + ", retriable=" + retriable + "}");
    }
}

/* =====================================================================
   DOMAIN SERVICE — PRODUCE per record (async) returning ProduceOutcome
   ===================================================================== */
interface PaymentService {
    CompletableFuture<ProduceOutcome> processPayment(ConsumerRecord<String, String> record);
}

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
    public CompletableFuture<ProduceOutcome> processPayment(ConsumerRecord<String, String> record) {
        ProducerRecord<String, String> out = new ProducerRecord<>(props.getTargetTopic(), record.key(), record.value());
        // Trace headers for source lineage
        out.headers().add("src-topic", record.topic().getBytes(StandardCharsets.UTF_8));
        out.headers().add("src-partition", Integer.toString(record.partition()).getBytes(StandardCharsets.UTF_8));
        out.headers().add("src-offset", Long.toString(record.offset()).getBytes(StandardCharsets.UTF_8));
        if (props.isCopyHeaders() && record.headers() != null) {
            for (Header h : record.headers()) {
                if (!h.key().startsWith("src-")) out.headers().add(h);
            }
        }

        Timer.Sample sample = Timer.start(metrics);

        ListenableFuture<SendResult<String, String>> lf = template.send(out);
        CompletableFuture<SendResult<String, String>> cf = new CompletableFuture<>();
        lf.addCallback(cf::complete, cf::completeExceptionally);

        return cf.handle((res, err) -> {
            String tag = (err == null) ? "success" : "failed";
            sample.stop(Timer.builder("kafka.producer.send.latency")
                    .tag("topic", props.getTargetTopic())
                    .tag("result", tag)
                    .register(metrics));
            metrics.counter("kafka.producer.send.count", "topic", props.getTargetTopic(), "result", tag).increment();

            if (err != null) {
                return ProduceOutcome.failure(props.getTargetTopic(),
                        (err instanceof CompletionException && err.getCause() != null) ? err.getCause() : err, false);
            }
            return ProduceOutcome.success(res.getRecordMetadata());
        }).toCompletableFuture();
    }
}

/* =====================================================================
   FALLBACK PaymentService (no KafkaTemplate)
   ===================================================================== */
@Service
@ConditionalOnMissingBean(PaymentService.class)
class DummyPaymentService implements PaymentService {
    private static final Logger log = LoggerFactory.getLogger(DummyPaymentService.class);
    @Override public CompletableFuture<ProduceOutcome> processPayment(ConsumerRecord<String, String> record) {
        log.info("Dummy process (no KafkaTemplate): key={}, value={}", record.key(), record.value());
        return CompletableFuture.completedFuture(
                ProduceOutcome.success("dummy", 0, 0L, System.currentTimeMillis())
        );
    }
}

/* =====================================================================
   ERROR STORE — JDBC (PostgreSQL), TRANSACTIONAL + IDEMPOTENT
   Stores exception stack trace (truncated) for diagnostics.
   ---------------------------------------------------------------------
   DDL:
   CREATE TABLE IF NOT EXISTS payment_error_log (
       id               BIGSERIAL PRIMARY KEY,
       topic            TEXT        NOT NULL,
       partition_no     INT         NOT NULL,
       record_offset    BIGINT      NOT NULL,
       msg_key          TEXT        NULL,
       msg_value        TEXT        NULL,
       exception_type   TEXT        NULL,
       error_message    TEXT        NULL,
       exception_stack  TEXT        NULL,
       source           VARCHAR(16) NOT NULL,
       occurred_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
       UNIQUE (topic, partition_no, record_offset)
   );
   ===================================================================== */
interface ErrorLogBatchService {
    void insertSingle(ErrorRecord record);
    void insertBatch(List<ErrorRecord> records);
}

@Repository
@ConditionalOnBean(JdbcTemplate.class)
class JdbcErrorLogBatchService implements ErrorLogBatchService {

    private static final Logger log = LoggerFactory.getLogger(JdbcErrorLogBatchService.class);

    private static final String INSERT_SQL = """
        INSERT INTO payment_error_log
            (topic, partition_no, record_offset, msg_key, msg_value,
             exception_type, error_message, exception_stack, source, occurred_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    r.getExceptionType(), r.getErrorMessage(), r.getExceptionStack(),
                    r.getSource(), Timestamp.from(r.getOccurredAt()));
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
                        ps.setString(idx++, r.getExceptionType());
                        ps.setString(idx++, r.getErrorMessage());
                        ps.setString(idx++, r.getExceptionStack());
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
   ERROR RECORD — immutable, full traceability + stack traces
   ===================================================================== */
final class ErrorRecord {
    private final String topic;
    private final int partition;
    private final long offset;
    private final String key;
    private final String valuePreview;   // truncated
    private final String exceptionType;
    private final String errorMessage;
    private final String exceptionStack;
    private final String source;         // SINGLE | BATCH | TIMEOUT
    private final Instant occurredAt;

    private ErrorRecord(String topic, int partition, long offset,
                        String key, String valuePreview,
                        String exceptionType, String errorMessage, String exceptionStack,
                        String source, Instant occurredAt) {
        this.topic = topic;
        this.partition = partition;
        this.offset = offset;
        this.key = key;
        this.valuePreview = valuePreview;
        this.exceptionType = exceptionType;
        this.errorMessage = errorMessage;
        this.exceptionStack = exceptionStack;
        this.source = source;
        this.occurredAt = occurredAt;
    }

    static ErrorRecord fromOutcome(ConsumerRecord<String, String> src, ProduceOutcome out, String source) {
        return new ErrorRecord(
                src.topic(), src.partition(), src.offset(),
                src.key(), truncate(src.value(), 2000),
                out.getExceptionType(), out.getErrorMessage(), out.getExceptionStack(),
                source, Instant.now()
        );
    }

    static ErrorRecord timeout(ConsumerRecord<String, String> src, long timeoutMs) {
        return new ErrorRecord(
                src.topic(), src.partition(), src.offset(),
                src.key(), truncate(src.value(), 2000),
                "TimeoutException", "Processing timed out after " + timeoutMs + " ms", null,
                "TIMEOUT", Instant.now()
        );
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // getters
    public String getTopic() { return topic; }
    public int getPartition() { return partition; }
    public long getOffset() { return offset; }
    public String getKey() { return key; }
    public String getValuePreview() { return valuePreview; }
    public String getExceptionType() { return exceptionType; }
    public String getErrorMessage() { return errorMessage; }
    public String getExceptionStack() { return exceptionStack; }
    public String getSource() { return source; }
    public Instant getOccurredAt() { return occurredAt; }

    @Override public String toString() {
        return "ErrorRecord{" +
                "topic='" + topic + '\'' +
                ", partition=" + partition +
                ", offset=" + offset +
                ", exceptionType='" + exceptionType + '\'' +
                ", source='" + source + '\'' +
                ", occurredAt=" + occurredAt +
                '}';
    }
}
