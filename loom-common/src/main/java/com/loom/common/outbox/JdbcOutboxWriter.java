package com.loom.common.outbox;

import com.loom.common.event.TaskEvent;
import com.loom.common.event.TaskResultEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Uses the caller's JPA/JDBC transaction; never creates an independent intent transaction. */
public class JdbcOutboxWriter {
    private final JdbcTemplate jdbc;

    public JdbcOutboxWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void jobCreated(UUID jobId) { insert(OutboxMessages.jobCreated(jobId)); }
    public void taskQueue(TaskEvent event, Instant availableAt) { insert(OutboxMessages.taskQueue(event, availableAt)); }
    public void taskResult(TaskResultEvent event) { insert(OutboxMessages.taskResult(event)); }

    public void insert(OutboxMessage message) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Outbox insertion requires the execution state transaction");
        jdbc.update("""
                INSERT INTO outbox_events(id,dedupe_key,topic,event_key,event_type,payload_json,available_at,created_at)
                VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP) ON CONFLICT (dedupe_key) DO NOTHING
                """, message.id(), message.dedupeKey(), message.topic(), message.eventKey(), message.eventType(),
                message.payloadJson(), Timestamp.from(message.availableAt()));
    }
}
