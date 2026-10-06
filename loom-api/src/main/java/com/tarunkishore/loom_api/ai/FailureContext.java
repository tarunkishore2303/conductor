package com.tarunkishore.loom_api.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Facts collected by code, never filled in by a model. */
public record FailureContext(
        UUID jobId, String jobStatus, List<TaskEvidence> tasks, List<String> unavailableEvidence) {
    public record TaskEvidence(
            UUID taskId,
            String name,
            String status,
            int maxRetries,
            List<Attempt> attempts,
            Instant deadLetteredAt) {}

    public record Attempt(
            Integer attemptNumber,
            String status,
            Instant startedAt,
            Instant completedAt,
            String errorType,
            String errorMessage,
            Instant retryScheduledAt) {}
}
