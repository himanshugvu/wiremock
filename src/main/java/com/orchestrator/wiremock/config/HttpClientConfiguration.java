package com.example.messaging.service;

import com.example.messaging.domain.EventStatus;
import com.example.messaging.domain.OutgoingEvent;
import com.example.messaging.dto.BatchResult;
import com.example.messaging.repository.EventStatusJdbcRepository;
import com.example.producer.KafkaGlobalEventProducer;
import com.example.producer.KafkaMessage;
import com.example.producer.ProducerResult;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrates sending events to Kafka and persisting results to MariaDB.
 */
@Service
public class EventProcessingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventProcessingService.class);

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;
    private static final long SEND_BATCH_TIMEOUT_SECONDS = 10L;

    private final KafkaGlobalEventProducer kafkaProducer;
    private final EventStatusJdbcRepository eventStatusRepository;
    private final Executor successWriteExecutor;

    public EventProcessingService(KafkaGlobalEventProducer kafkaProducer,
                                  EventStatusJdbcRepository eventStatusRepository,
                                  @Qualifier("successWriteExecutor") Executor successWriteExecutor) {
        this.kafkaProducer = kafkaProducer;
        this.eventStatusRepository = eventStatusRepository;
        this.successWriteExecutor = successWriteExecutor;
    }

    /**
     * Main batch flow:
     *  - async send all events to Kafka using KafkaGlobalEventProducer
     *  - wait for all futures to complete (with timeout)
     *  - sync batch insert FAILED into MariaDB
     *  - async batch insert SUCCESS into MariaDB
     */
    public BatchResult processBatch(List<OutgoingEvent> events, Headers headers) {

        // Holder for each send: our domain event + future<ProducerResult>
        record SendHolder(
                OutgoingEvent event,
                CompletableFuture<ProducerResult> future
        ) {}

        List<SendHolder> sends = new ArrayList<>(events.size());

        // 1) Async sends; producer-level retries configured via properties.
        for (OutgoingEvent event : events) {
            // You already know how to convert OutgoingEvent -> KafkaMessage
            KafkaMessage kafkaMessage = toKafkaMessage(event); // <-- implement mapping

            CompletableFuture<ProducerResult> future =
                    kafkaProducer.publishEvents(kafkaMessage, headers);

            sends.add(new SendHolder(event, future));
        }

        // 2) Wait for all futures to complete, but cap with a batch timeout.
        CompletableFuture<?>[] futuresArray =
                sends.stream().map(SendHolder::future).toArray(CompletableFuture[]::new);

        CompletableFuture<Void> all = CompletableFuture
                .allOf(futuresArray)
                .orTimeout(SEND_BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        try {
            all.join();
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof TimeoutException te) {
                LOGGER.warn(
                        "Timed out waiting for Kafka sends to complete ({} seconds).",
                        SEND_BATCH_TIMEOUT_SECONDS,
                        te
                );
            } else {
                LOGGER.warn(
                        "Kafka sends completed exceptionally at batch level",
                        ex.getCause()
                );
            }
            // We still classify each future below.
        }

        // 3) Build lists of EventStatus for successes and failures.
        List<EventStatus> successStatuses = new ArrayList<>();
        List<EventStatus> failureStatuses = new ArrayList<>();

        for (SendHolder holder : sends) {
            CompletableFuture<ProducerResult> future = holder.future();

            if (!future.isDone()) {
                // Didn't finish within SEND_BATCH_TIMEOUT_SECONDS → treat as failed.
                LOGGER.error("Kafka send TIMED OUT for eventId={}", holder.event().id());
                failureStatuses.add(
                        EventStatus.failure(
                                holder.event(),
                                "Timed out waiting for Kafka send after " +
                                        SEND_BATCH_TIMEOUT_SECONDS + " seconds"
                        )
                );
                continue;
            }

            ProducerResult result;
            try {
                // NOTE: publishEvents already mapped exception -> ProducerResult,
                // so this join should normally not throw. We still guard it.
                result = future.join();
            } catch (CompletionException ex) {
                LOGGER.error("Kafka send FAILED with exception for eventId={}",
                        holder.event().id(), ex);
                failureStatuses.add(
                        EventStatus.failure(
                                holder.event(),
                                truncate(ex.getMessage(), MAX_ERROR_MESSAGE_LENGTH)
                        )
                );
                continue;
            }

            // Decide success vs failure based on ProducerResult **content**
            if (result.isSuccess()) { // <-- adapt to your actual API
                LOGGER.debug("Kafka send OK for eventId={}", holder.event().id());
                successStatuses.add(EventStatus.success(holder.event()));
            } else {
                LOGGER.error("Kafka send FAILED for eventId={} : {}",
                        holder.event().id(), result.errorMessage()); // adapt accessor name
                failureStatuses.add(
                        EventStatus.failure(
                                holder.event(),
                                truncate(result.errorMessage(), MAX_ERROR_MESSAGE_LENGTH)
                        )
                );
            }
        }

        // 4) Synchronous batch insert of failed events.
        if (!failureStatuses.isEmpty()) {
            eventStatusRepository.batchInsert(failureStatuses);
        }

        // 5) Asynchronous batch insert of successful events.
        if (!successStatuses.isEmpty()) {
            CompletableFuture.runAsync(
                    () -> eventStatusRepository.batchInsert(successStatuses),
                    successWriteExecutor
            ).exceptionally(ex -> {
                LOGGER.error("Async batch insert for successful events FAILED", ex);
                return null;
            });
        }

        return new BatchResult(
                events.size(),
                successStatuses.size(),
                failureStatuses.size()
        );
    }

    private KafkaMessage toKafkaMessage(OutgoingEvent event) {
        // TODO: map your domain event into the KafkaMessage used by KafkaGlobalEventProducer
        // e.g. return new KafkaMessage(event.id(), event.payload(), ...);
        throw new UnsupportedOperationException("Implement OutgoingEvent -> KafkaMessage mapping");
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
