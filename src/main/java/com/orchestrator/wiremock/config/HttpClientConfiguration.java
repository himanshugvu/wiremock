package com.example.demo;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Service
public class EventProcessingService {

    private static final Logger log = LoggerFactory.getLogger(EventProcessingService.class);

    private static final String INSERT_SQL =
            "INSERT INTO event_status (id, payload, status, error_message) VALUES (?, ?, ?, ?)";

    private final KafkaTemplate<String, OutgoingEvent> kafkaTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final String topic;

    // Virtual-thread executor for async DB writes (fast + cheap concurrency)
    private final Executor successWriteExecutor =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("success-db-", 0).factory()
            );

    public EventProcessingService(KafkaTemplate<String, OutgoingEvent> kafkaTemplate,
                                  JdbcTemplate jdbcTemplate,
                                  @Value("${app.kafka.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.topic = topic;
    }

    /**
     * Main batch flow:
     *  - async send all events to Kafka
     *  - wait for all futures to complete
     *  - sync batch insert FAILED into MariaDB
     *  - async batch insert SUCCESS into MariaDB
     */
    public BatchResult processBatch(List<OutgoingEvent> events) {

        record SendHolder(
                OutgoingEvent event,
                CompletableFuture<SendResult<String, OutgoingEvent>> future
        ) {}

        List<SendHolder> sends = new ArrayList<>(events.size());

        // 1) async sends (Kafka producer itself can retry via config)
        for (OutgoingEvent event : events) {
            CompletableFuture<SendResult<String, OutgoingEvent>> future =
                    kafkaTemplate.send(topic, event.id(), event);
            sends.add(new SendHolder(event, future));
        }

        // 2) wait for all futures to complete (success OR final failure)
        CompletableFuture<?>[] futuresArray =
                sends.stream().map(SendHolder::future).toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futuresArray)
                .exceptionally(ex -> {
                    log.warn("Some Kafka sends completed exceptionally at allOf level", ex);
                    return null;
                })
                .join(); // safe from platform or virtual thread

        // 3) partition successes and failures
        List<OutgoingEvent> successEvents = new ArrayList<>();
        List<FailedEvent> failedEvents = new ArrayList<>();

        for (SendHolder holder : sends) {
            holder.future()
                    .handle((sendResult, ex) -> {
                        if (ex != null) {
                            log.error("Kafka send FAILED for eventId={}", holder.event().id(), ex);
                            failedEvents.add(new FailedEvent(holder.event(), truncate(ex.getMessage(), 1000)));
                        } else {
                            RecordMetadata meta = sendResult.getRecordMetadata();
                            log.debug("Kafka send OK eventId={} topic={} partition={} offset={}",
                                      holder.event().id(),
                                      meta.topic(),
                                      meta.partition(),
                                      meta.offset());
                            successEvents.add(holder.event());
                        }
                        return null;
                    })
                    .join();
        }

        // 4) sync batch insert for failed events (caller waits)
        if (!failedEvents.isEmpty()) {
            batchInsertFailed(failedEvents);
        }

        // 5) async batch insert for successful events (caller does NOT wait)
        if (!successEvents.isEmpty()) {
            CompletableFuture.runAsync(
                    () -> batchInsertSuccess(successEvents),
                    successWriteExecutor
            ).exceptionally(ex -> {
                log.error("Async batch insert for successful events FAILED", ex);
                return null;
            });
        }

        return new BatchResult(
                events.size(),
                successEvents.size(),
                failedEvents.size()
        );
    }

    // -------------------------------------------------------------------------
    // Batch insert helpers (MariaDB via JdbcTemplate)
    // -------------------------------------------------------------------------

    private void batchInsertFailed(List<FailedEvent> failedEvents) {
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                FailedEvent fe = failedEvents.get(i);
                ps.setString(1, fe.event().id());
                ps.setString(2, fe.event().payload());
                ps.setString(3, "FAILED");
                ps.setString(4, fe.errorMessage());
            }

            @Override
            public int getBatchSize() {
                return failedEvents.size();
            }
        });
    }

    private void batchInsertSuccess(List<OutgoingEvent> successEvents) {
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                OutgoingEvent ev = successEvents.get(i);
                ps.setString(1, ev.id());
                ps.setString(2, ev.payload());
                ps.setString(3, "SUCCESS");
                ps.setString(4, null); // no error message
            }

            @Override
            public int getBatchSize() {
                return successEvents.size();
            }
        });
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return (s.length() <= max) ? s : s.substring(0, max);
    }

    // -------------------------------------------------------------------------
    // Simple DTOs / records (can be moved out if you prefer)
    // -------------------------------------------------------------------------

    // Event you send to Kafka
    public record OutgoingEvent(
            String id,
            String payload
    ) {}

    // Internal holder for failed events (event + error)
    private record FailedEvent(
            OutgoingEvent event,
            String errorMessage
    ) {}

    // Summary returned to caller
    public record BatchResult(
            int total,
            int successCount,
            int failureCount
    ) {}
}
