package com.tarunkishore.loom_api.dto;

import com.loom.common.model.Task;

import java.time.Instant;
import java.util.UUID;

public record DlqTaskResponse(
    UUID taskId,
    UUID jobId,
    String taskName,
    int maxRetries,
    int retryCount,
    Instant updatedAt
) {
    public static DlqTaskResponse from(Task t) {
        return new DlqTaskResponse(
            t.getId(), t.getJob().getId(), t.getName(),
            t.getMaxRetries(), t.getRetryCount(), t.getUpdatedAt()
        );
    }
}
