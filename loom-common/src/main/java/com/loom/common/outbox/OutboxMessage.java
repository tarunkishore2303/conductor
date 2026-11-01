package com.loom.common.outbox;

import java.time.Instant;
import java.util.UUID;

/** Immutable execution event intent. Never contains task configuration or output. */
public record OutboxMessage(UUID id, String dedupeKey, String topic, String eventKey,
        String eventType, String payloadJson, Instant availableAt) {}
