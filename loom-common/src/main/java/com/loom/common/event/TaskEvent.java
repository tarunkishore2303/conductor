package com.loom.common.event;

import java.util.UUID;

public record TaskEvent(
    UUID jobId,
    UUID taskId,
    String taskName,
    int maxRetries,
    int attemptNumber
) {}
