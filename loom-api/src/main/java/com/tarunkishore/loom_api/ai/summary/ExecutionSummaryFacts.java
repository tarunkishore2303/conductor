package com.tarunkishore.loom_api.ai.summary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Code-derived observations. No field is populated by a language model. */
public record ExecutionSummaryFacts(
        UUID jobId, String name, String status, Instant createdAt,
        Instant firstObservedAttemptAt, Instant lastObservedCompletedAttemptAt,
        Long observedAttemptSpanMillis, int taskCount, int completedTasks, int failedTasks,
        int deadLetteredTasks, int cancelledTasks, int blockedPendingTasks,
        int totalRecordedAttempts, long recordedRetries, boolean retryHistoryComplete,
        long aggregateRecordedTaskDurationMillis, boolean recordedTimingComplete,
        List<SlowTask> slowestObservedTasks, UUID failureAnalysisId,
        String evidenceFingerprint, List<String> unavailableEvidence) {
    public record SlowTask(UUID taskId, String name, long durationMillis, Double shareOfAggregate) {}
}
