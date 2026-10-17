package com.tarunkishore.loom_api.ai.optimization;

import com.loom.capability.TaskType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bounded code-derived evidence. Configuration and raw error messages never enter this context. */
public record OptimizationContext(UUID workflowId, Long workflowVersion, List<TaskStructure> tasks,
        List<Run> runs, boolean historyTruncated, int excludedRuns, int minSampleSize,
        int maxCandidates, long historyWindowSeconds, List<String> limitations) {
    public record TaskStructure(String taskId, TaskType type, List<String> dependsOn) {}
    public record Run(UUID jobId, String status, Instant createdAt, List<TaskRun> tasks) {}
    public record TaskRun(UUID taskId, String logicalId, String status, Instant deadLetteredAt,
            List<Attempt> attempts) {}
    public record Attempt(Integer attemptNumber, String status, Instant startedAt, Instant completedAt,
            String errorType, Instant retryScheduledAt) {}
}
