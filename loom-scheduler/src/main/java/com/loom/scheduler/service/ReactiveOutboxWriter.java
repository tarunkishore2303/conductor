package com.loom.scheduler.service;

import com.loom.common.event.TaskEvent;
import com.loom.common.outbox.OutboxMessages;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Uses the scheduler's transaction-bound R2DBC connection; never contacts Kafka. */
@Component
public final class ReactiveOutboxWriter {
    private final DatabaseClient database;

    public ReactiveOutboxWriter(DatabaseClient database) {
        this.database = database;
    }

    public Mono<Void> taskQueue(TaskEvent event) {
        return TransactionSynchronizationManager.forCurrentTransaction()
            .flatMap(transaction -> {
                if (!transaction.isActualTransactionActive()) {
                    return Mono.error(new IllegalStateException("Outbox insertion requires a transaction"));
                }
                var message = OutboxMessages.taskQueue(event, Instant.now());
                return database.sql("""
                    INSERT INTO outbox_events
                        (id, dedupe_key, topic, event_key, event_type, payload_json, available_at, created_at)
                    VALUES (:id, :dedupe, :topic, :key, :type, :payload, :available, CURRENT_TIMESTAMP)
                    ON CONFLICT (dedupe_key) DO NOTHING
                    """)
                    .bind("id", message.id()).bind("dedupe", message.dedupeKey())
                    .bind("topic", message.topic()).bind("key", message.eventKey())
                    .bind("type", message.eventType()).bind("payload", message.payloadJson())
                    .bind("available", OffsetDateTime.ofInstant(message.availableAt(), ZoneOffset.UTC))
                    .fetch().rowsUpdated().then();
            });
    }
}
