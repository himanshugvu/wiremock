/*
==========================
= 1) SQL DDL (MariaDB)   =
==========================

Use this to create the table (or adjust your existing one).

CREATE TABLE event_details (
    id                 BIGINT UNSIGNED NOT NULL,

    event_trace_id     VARCHAR(100)    NOT NULL,
    account_number     VARCHAR(64)     NOT NULL,
    customer_type      VARCHAR(32)     NOT NULL,

    event_timestamp_ms BIGINT          NOT NULL,

    topic              VARCHAR(200)    NOT NULL,
    partition_id       INT             NOT NULL,
    kafka_offset       BIGINT          NOT NULL,
    message_key        VARCHAR(256)    NULL,

    source_payload      MEDIUMTEXT     NULL,
    transformed_payload MEDIUMTEXT     NULL,

    exception_type     VARCHAR(255)    NULL,
    exception_message  TEXT            NULL,
    exception_stack    MEDIUMTEXT      NULL,

    retriable          TINYINT(1)      NOT NULL DEFAULT 0,
    retry_attempt      INT             NOT NULL DEFAULT 0,

    status             VARCHAR(20)     NOT NULL DEFAULT 'SUCCESS',

    created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                          ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    UNIQUE KEY uk_topic_partition_offset (topic, partition_id, kafka_offset),
    KEY idx_account_number (account_number),
    KEY idx_event_trace_id (event_trace_id),
    KEY idx_partition (partition_id),
    KEY idx_kafka_offset (kafka_offset)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- If table already exists, just add status:
-- ALTER TABLE event_details
--   ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' AFTER retry_attempt;


==========================
= 1) application.yml     =
==========================

Configure the table name here. You can change it per env.

app:
  event-details-table: event_details


==========================================
= 2) Spring JDBC repository (TSID + enum)
=    with table name from app.yml
==========================================
*/

package com.example.messaging.repository;

import com.example.messaging.model.EventDetails;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.tsid.TsidCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

@Repository
public class EventStatusJdbcRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventStatusJdbcRepository.class);

    // We’ll build this from the table name in the constructor
    private final String insertSql;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    // Status enum (stored as VARCHAR(20) in "status" column)
    public enum EventStatus {
        SUCCESS,
        FAILED,
        PENDING,
        RETRIED,
        SKIPPED,
        PARTIAL
    }

    public EventStatusJdbcRepository(
            JdbcTemplate jdbcTemplate,
            @Value("${app.event-details-table:event_details}") String tableName
    ) {
        this.jdbcTemplate = jdbcTemplate;
        // Safeguard: no quotes or weird things in tableName
        this.insertSql = buildInsertSql(sanitizeTableName(tableName));
        LOGGER.info("EventStatusJdbcRepository using table '{}'", tableName);
    }

    // Build the INSERT statement using the configured table name
    private String buildInsertSql(String tableName) {
        return """
            INSERT INTO %s (
                id,
                event_trace_id,
                account_number,
                customer_type,
                event_timestamp_ms,
                topic,
                partition_id,
                kafka_offset,
                message_key,
                source_payload,
                transformed_payload,
                exception_type,
                exception_message,
                exception_stack,
                retriable,
                retry_attempt,
                status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.formatted(tableName);
    }

    // Very simple sanitiser to avoid backticks/injection in table name
    private String sanitizeTableName(String tableName) {
        // Allow only letters, digits, underscore
        if (!tableName.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid table name: " + tableName);
        }
        return tableName;
    }

    // ---------------------------------------------------------------------
    // TSID generator wrapper (64-bit time-ordered ID)
    // ---------------------------------------------------------------------
    private long nextId() {
        // Uses node from -Dtsidcreator.node or env TSIDCREATOR_NODE
        return TsidCreator.getTsid().toLong();
    }

    // ---------------------------------------------------------------------
    // Single insert: FAILED record
    // ---------------------------------------------------------------------
    public boolean persistFailedRecord(EventDetails event) {
        long id = nextId();
        try {
            int updated = jdbcTemplate.update(insertSql, ps -> {
                setCommonFields(ps, id, event);
                ps.setString(12, event.getExceptionType());
                ps.setString(13, event.getExceptionMessage());
                ps.setString(14, event.getExceptionStack());
                ps.setBoolean(15, event.isRetriable());
                ps.setInt(16, event.getRetryAttempt());
                ps.setString(17, EventStatus.FAILED.name());
            });
            return updated == 1;
        } catch (DuplicateKeyException ex) {
            LOGGER.info("Duplicate key on persistFailedRecord (treating as success). eventTraceId={}",
                    event.getEventTraceId(), ex);
            return true;
        } catch (DataAccessException ex) {
            LOGGER.error("DB error on persistFailedRecord. eventTraceId={}", event.getEventTraceId(), ex);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Single insert: SUCCESS record
    // ---------------------------------------------------------------------
    public boolean persistSuccessRecord(EventDetails event) {
        long id = nextId();
        try {
            int updated = jdbcTemplate.update(insertSql, ps -> {
                setCommonFields(ps, id, event);
                ps.setString(12, null);
                ps.setString(13, null);
                ps.setString(14, null);
                ps.setBoolean(15, false); // success not retriable
                ps.setInt(16, event.getRetryAttempt() != null ? event.getRetryAttempt() : 0);
                ps.setString(17, EventStatus.SUCCESS.name());
            });
            return updated == 1;
        } catch (DuplicateKeyException ex) {
            LOGGER.info("Duplicate key on persistSuccessRecord (treating as success). eventTraceId={}",
                    event.getEventTraceId(), ex);
            return true;
        } catch (DataAccessException ex) {
            LOGGER.error("DB error on persistSuccessRecord. eventTraceId={}", event.getEventTraceId(), ex);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Batch insert: FAILED records
    // ---------------------------------------------------------------------
    public boolean batchPersistFailedRecords(List<EventDetails> events) {
        if (events == null || events.isEmpty()) {
            return true;
        }

        try {
            int[] results = jdbcTemplate.batchUpdate(insertSql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    EventDetails event = events.get(i);
                    long id = nextId();

                    setCommonFields(ps, id, event);
                    ps.setString(12, event.getExceptionType());
                    ps.setString(13, event.getExceptionMessage());
                    ps.setString(14, event.getExceptionStack());
                    ps.setBoolean(15, event.isRetriable());
                    ps.setInt(16, event.getRetryAttempt());
                    ps.setString(17, EventStatus.FAILED.name());
                }

                @Override
                public int getBatchSize() {
                    return events.size();
                }
            });

            return allBatchSucceeded(results, events.size());
        } catch (DuplicateKeyException ex) {
            LOGGER.info("Duplicate key in batchPersistFailedRecords (treating batch as success). size={}",
                    events.size(), ex);
            return true;
        } catch (DataAccessException ex) {
            LOGGER.error("DB error in batchPersistFailedRecords. size={}", events.size(), ex);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Batch insert: SUCCESS records
    // ---------------------------------------------------------------------
    public boolean batchPersistSuccessRecords(List<EventDetails> events) {
        if (events == null || events.isEmpty()) {
            return true;
        }

        try {
            int[] results = jdbcTemplate.batchUpdate(insertSql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    EventDetails event = events.get(i);
                    long id = nextId();

                    setCommonFields(ps, id, event);
                    ps.setString(12, null);
                    ps.setString(13, null);
                    ps.setString(14, null);
                    ps.setBoolean(15, false);
                    ps.setInt(16, event.getRetryAttempt() != null ? event.getRetryAttempt() : 0);
                    ps.setString(17, EventStatus.SUCCESS.name());
                }

                @Override
                public int getBatchSize() {
                    return events.size();
                }
            });

            return allBatchSucceeded(results, events.size());
        } catch (DuplicateKeyException ex) {
            LOGGER.info("Duplicate key in batchPersistSuccessRecords (treating batch as success). size={}",
                    events.size(), ex);
            return true;
        } catch (DataAccessException ex) {
            LOGGER.error("DB error in batchPersistSuccessRecords. size={}", events.size(), ex);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Batch helper
    // ---------------------------------------------------------------------
    private boolean allBatchSucceeded(int[] results, int expectedSize) {
        if (results == null || results.length != expectedSize) {
            LOGGER.warn("Batch update size mismatch. expected={} actual={}",
                    expectedSize, results == null ? null : results.length);
            return false;
        }
        for (int count : results) {
            if (count == Statement.EXECUTE_FAILED) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Common fields for success/failure
    // ---------------------------------------------------------------------
    private void setCommonFields(PreparedStatement ps, long id, EventDetails event) throws SQLException {
        ps.setLong(1, id);
        ps.setString(2, event.getEventTraceId());
        ps.setString(3, event.getAccountNumber());
        ps.setString(4, event.getCustomerType());
        ps.setLong(5, event.getTimestamp());    // epoch millis
        ps.setString(6, event.getTopic());
        ps.setInt(7, event.getPartition());
        ps.setLong(8, event.getOffset());
        ps.setString(9, event.getKey());

        ps.setString(10, toJson(event.getSourcePayload()));
        ps.setString(11, toJson(event.getTransformedPayload()));
    }

    private String toJson(JsonNode node) throws SQLException {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to serialize JsonNode to JSON string", e);
        }
    }
}
