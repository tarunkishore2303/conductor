package com.loom.common.outbox;

import com.loom.common.event.JobCreatedEvent;
import com.loom.common.event.TaskEvent;
import com.loom.common.event.TaskResultEvent;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Only the three application-owned execution contracts may enter the outbox. */
public final class OutboxMessages {
    public static final int MAX_PAYLOAD_BYTES = 32768;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private OutboxMessages() {}

    public static OutboxMessage jobCreated(UUID jobId) {
        return message("job-created:" + jobId, "job-created", jobId.toString(),
                "JobCreatedEvent", new JobCreatedEvent(jobId), Instant.now());
    }

    public static OutboxMessage taskQueue(TaskEvent event, Instant availableAt) {
        if (event.generation() < 0 || event.attemptNumber() < 0)
            throw new IllegalArgumentException("Negative execution identity");
        return message("task-queue:" + event.taskId() + ":" + event.generation() + ":" + event.attemptNumber(),
                "task-queue", event.taskId().toString(), "TaskEvent", event, availableAt);
    }

    public static OutboxMessage taskResult(TaskResultEvent event) {
        return message("task-result:" + event.executionId() + ":" + event.status(), "task-results",
                event.taskId().toString(), "TaskResultEvent", event, Instant.now());
    }

    private static OutboxMessage message(String dedupe, String topic, String key, String type,
            Object event, Instant availableAt) {
        String json = JSON.writeValueAsString(event);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("Execution event exceeds outbox limit");
        return new OutboxMessage(UUID.randomUUID(), dedupe, topic, key, type, json, availableAt);
    }
}
