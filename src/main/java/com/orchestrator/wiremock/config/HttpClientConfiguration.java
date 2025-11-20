package com.example.messaging.repository;

import com.example.messaging.model.EventDetails;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.tsid.TsidCreator;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public EventStatusJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------------------------------------------------------------
    // TSID generator wrapper (64-bit time-ordered ID)
    // ---------------------------------------------------------------------
    private long nextId() {
        // Uses node from -Dtsidcreator.node or env TSIDCREATOR_NODE
        return TsidCreator.getTsid().toLong();
    }

    // ---------------------------------------------------------------------
    // Single insert: FAILED record  -> returns true if 1 row inserted
    // ---------------------------------------------------------------------
    public boolean persistFailedRecord(EventDetails event) {
        long id = nextId();

        int updated = jdbcTemplate.update(INSERT_SQL, ps -> {
            setCommonFields(ps, id, event);
            ps.setString(12, event.getExceptionType());
            ps.setString(13, event.getExceptionMessage());
            ps.setString(14, event.getExceptionStack());
            ps.setBoolean(15, event.isRetriable());
            ps.setInt(16, event.getRetryAttempt());
        });

        return updated == 1;
    }

    // ---------------------------------------------------------------------
    // Single insert: SUCCESS record -> returns true if 1 row inserted
    // ---------------------------------------------------------------------
    public boolean persistSuccessRecord(EventDetails event) {
        long id = nextId();

        int updated = jdbcTemplate.update(INSERT_SQL, ps -> {
            setCommonFields(ps, id, event);
            ps.setString(12, null);
            ps.setString(13, null);
            ps.setString(14, null);
            ps.setBoolean(15, false); // success not retriable
            ps.setInt(16, event.getRetryAttempt() != null ? event.getRetryAttempt() : 0);
        });

        return updated == 1;
    }

    // ---------------------------------------------------------------------
    // Batch insert: FAILED records -> true if all succeeded
    // ---------------------------------------------------------------------
    public boolean batchPersistFailedRecords(List<EventDetails> events) {
        if (events == null || events.isEmpty()) {
            return true; // nothing to insert = trivially OK
        }

        int[] results = jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
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
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });

        return allBatchSucceeded(results, events.size());
    }

    // ---------------------------------------------------------------------
    // Batch insert: SUCCESS records -> true if all succeeded
    // ---------------------------------------------------------------------
    public boolean batchPersistSuccessRecords(List<EventDetails> events) {
        if (events == null || events.isEmpty()) {
            return true; // nothing to insert = trivially OK
        }

        int[] results = jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
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
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });

        return allBatchSucceeded(results, events.size());
    }

    // ---------------------------------------------------------------------
    // Batch helper: check that all batched statements succeeded
    // ---------------------------------------------------------------------
    private boolean allBatchSucceeded(int[] results, int expectedSize) {
        if (results == null || results.length != expectedSize) {
            return false;
        }
        for (int count : results) {
            // EXECUTE_FAILED = -3; SUCCESS_NO_INFO = -2; positive = rows affected
            if (count == Statement.EXECUTE_FAILED) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Common fields for success/failure
    // sourcePayload & transformedPayload are JsonNode
    // ---------------------------------------------------------------------
    private void setCommonFields(PreparedStatement ps, long id, EventDetails event) throws SQLException {
        ps.setLong(1, id);
        ps.setString(2, event.getEventTraceId());
        ps.setString(3, event.getAccountNumber());
        ps.setString(4, event.getCustomerType());
        ps.setLong(5, event.getTimestamp());    // epoch millis -> event_timestamp_ms
        ps.setString(6, event.getTopic());
        ps.setInt(7, event.getPartition());
        ps.setLong(8, event.getOffset());
        ps.setString(9, event.getKey());

        // JsonNode -> JSON string
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
