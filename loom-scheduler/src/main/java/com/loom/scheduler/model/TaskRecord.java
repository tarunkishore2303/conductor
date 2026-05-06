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
    Long version
) {}
