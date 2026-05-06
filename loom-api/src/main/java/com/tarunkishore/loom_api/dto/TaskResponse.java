package com.tarunkishore.loom_api.dto;

import com.loom.common.model.Task;
import com.loom.common.model.TaskStatus;

import java.time.Instant;
import java.util.UUID;

public record TaskResponse(
    UUID id,
    String name,
    TaskStatus status,
    int maxRetries,
    int retryCount,
    Instant createdAt,
    Instant updatedAt
) {
    public static TaskResponse from(Task t) {
        return new TaskResponse(
            t.getId(), t.getName(), t.getStatus(),
            t.getMaxRetries(), t.getRetryCount(),
            t.getCreatedAt(), t.getUpdatedAt()
        );
    }
}
