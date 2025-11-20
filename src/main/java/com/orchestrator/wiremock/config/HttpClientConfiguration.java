-- ============================================================
-- 1) MariaDB / MySQL DDL for event_details (TSID as BIGINT PK)
-- ============================================================

CREATE TABLE event_details (
    id                 BIGINT UNSIGNED NOT NULL,

    event_trace_id     VARCHAR(100)    NOT NULL,
    account_number     VARCHAR(64)     NOT NULL,
    customer_type      VARCHAR(32)     NOT NULL,

    event_timestamp_ms BIGINT          NOT NULL,  -- epoch millis

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

    created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                          ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    -- Kafka uniqueness per record
    UNIQUE KEY uk_topic_partition_offset (topic, partition_id, kafka_offset),

    -- Helpful secondary indexes (tune as needed)
    KEY idx_event_trace_id (event_trace_id),
    KEY idx_account_number (account_number),
    KEY idx_partition (partition_id),
    KEY idx_kafka_offset (kafka_offset)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;


-- ============================================================
-- 2) Maven dependency for TSID
-- ============================================================

<!-- TSID (time-sortable 64-bit IDs) -->
<dependency>
  <groupId>com.github.f4b6a3</groupId>
  <artifactId>tsid-creator</artifactId>
  <version>5.2.6</version>
</dependency>


-- ============================================================
-- 3) Spring JdbcTemplate repository using TSID as BIGINT
--    - Methods:
--        persistFailedRecord(EventDetails)
--        persistSuccessRecord(EventDetails)
--        batchPersistFailedRecords(List<EventDetails>)
--        batchPersistSuccessRecords(List<EventDetails>)
-- ============================================================

package com.example.messaging.repository;

import com.example.messaging.model.EventDetails;
import com.github.f4b6a3.tsid.TsidCreator;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * Repository for persisting EventDetails to the event_details table using TSID
 * (time-sortable 64-bit IDs) as BIGINT primary key.
 *
 * TSID node configuration (so IDs are unique across pods/nodes):
 *   - System property: -Dtsidcreator.node=<int>
 *   - Env var:         TSIDCREATOR_NODE=<int>
 *
 * In OCP/Kubernetes, typically set TSIDCREATOR_NODE via env on the pod.
 */
@Repository
public class EventStatusJdbcRepository {

    private static final String INSERT_SQL = """
        INSERT INTO event_details (
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
            retry_attempt
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbcTemplate;
    private final TsidIdGenerator idGenerator;

    public EventStatusJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.idGenerator = new TsidIdGenerator();
    }

    // ---------------------------------------------------------------------
    // Single insert: FAILED record
    // ---------------------------------------------------------------------
    public void persistFailedRecord(EventDetails event) {
        long id = idGenerator.nextId();

        jdbcTemplate.update(INSERT_SQL, ps -> {
            setCommonFields(ps, id, event);
            ps.setString(12, event.getExceptionType());
            ps.setString(13, event.getExceptionMessage());
            ps.setString(14, event.getExceptionStack());
            ps.setBoolean(15, event.isRetriable());
            ps.setInt(16, event.getRetryAttempt());
        });
    }

    // ---------------------------------------------------------------------
    // Single insert: SUCCESS record
    // ---------------------------------------------------------------------
    public void persistSuccessRecord(EventDetails event) {
        long id = idGenerator.nextId();

        jdbcTemplate.update(INSERT_SQL, ps -> {
            setCommonFields(ps, id, event);
            ps.setString(12, null);
            ps.setString(13, null);
            ps.setString(14, null);
            ps.setBoolean(15, false); // success is not retriable
            ps.setInt(16, event.getRetryAttempt() != null ? event.getRetryAttempt() : 0);
        });
    }

    // ---------------------------------------------------------------------
    // Batch insert: FAILED records
    // ---------------------------------------------------------------------
    public void batchPersistFailedRecords(List<EventDetails> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                EventDetails event = events.get(i);
                long id = idGenerator.nextId();

                setCommonFields(ps, id, event);
                ps.setString(12, event.getExceptionType());
                ps.setString(13, event.getExceptionMessage());
                ps.setString(14, event.getExceptionStack());
                ps.setBoolean(15, event.isRetriable());
                ps.setInt(16, event.getRetryAttempt());
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });
    }

    // ---------------------------------------------------------------------
    // Batch insert: SUCCESS records
    // ---------------------------------------------------------------------
    public void batchPersistSuccessRecords(List<EventDetails> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                EventDetails event = events.get(i);
                long id = idGenerator.nextId();

                setCommonFields(ps, id, event);
                ps.setString(12, null);
                ps.setString(13, null);
                ps.setString(14, null);
                ps.setBoolean(15, false);
                ps.setInt(16, event.getRetryAttempt() != null ? event.getRetryAttempt() : 0);
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });
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
        ps.setString(10, event.getSourcePayload());
        ps.setString(11, event.getTransformedPayload());
    }

    // ---------------------------------------------------------------------
    // Inner TSID generator wrapper
    // ---------------------------------------------------------------------
    private static final class TsidIdGenerator {

        /**
         * Generates a time-ordered 64-bit TSID as long.
         *
         * Configure node id via:
         *   -Dtsidcreator.node=<int>  or  env TSIDCREATOR_NODE=<int>
         */
        long nextId() {
            return TsidCreator.getTsid().toLong();
        }
    }
}
