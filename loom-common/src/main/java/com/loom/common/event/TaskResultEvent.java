package com.loom.common.event;

import com.loom.common.model.TaskStatus;

import java.util.UUID;

public record TaskResultEvent(
    UUID jobId,
    UUID taskId,
    UUID executionId,
    TaskStatus status,
    String errorMessage
) {}
