package com.loom.scheduler.model;

import com.loom.common.model.TaskStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("tasks")
public record TaskRecord(
    @Id UUID id,
    UUID jobId,
    String name,
    TaskStatus status,
    int maxRetries,
    int retryCount,
    Long version,
    int executionGeneration
) {
    public TaskRecord(UUID id, UUID jobId, String name, TaskStatus status, int maxRetries,
            int retryCount, Long version) {
        this(id, jobId, name, status, maxRetries, retryCount, version, 0);
    }
}
