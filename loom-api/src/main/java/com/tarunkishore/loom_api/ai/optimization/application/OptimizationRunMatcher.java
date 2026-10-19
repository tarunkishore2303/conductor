package com.tarunkishore.loom_api.ai.optimization.application;

import com.loom.capability.TaskConfigCodec;
import com.loom.common.dto.TaskDefinition;
import com.loom.common.model.Job;
import com.loom.common.model.JobStatus;
import com.loom.common.model.Task;
import com.loom.common.model.TaskExecution;
import com.loom.common.model.TaskStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Determines eligibility without guessing historical workflow identity or ignoring pending retries. */
final class OptimizationRunMatcher {
    private final TaskConfigCodec codec;

    OptimizationRunMatcher(TaskConfigCodec codec) {
        this.codec = codec;
    }

    boolean matches(List<Task> rows, Map<String, TaskDefinition> definitions, Map<UUID, Set<String>> dependencies) {
        if (rows.size() != definitions.size() || rows.size() > 50) return false;
        var seen = new HashSet<String>();
        for (var task : rows) {
            if (task.getLogicalId() == null) return false;
            var definition = definitions.get(task.getLogicalId());
            if (definition == null || !seen.add(task.getLogicalId()) || definition.type() != task.getTaskType()
                    || definition.maxRetries() != task.getMaxRetries()
                    || !new HashSet<>(definition.dependsOn()).equals(dependencies.getOrDefault(task.getId(), Set.of()))) return false;
            try { if (!Objects.equals(codec.decode(task.getTaskType(), task.getTaskConfigJson()), definition.config())) return false; }
            catch (RuntimeException invalid) { return false; }
        }
        return true;
    }

    boolean settled(Job job, List<Task> tasks, Map<UUID, List<TaskExecution>> attempts) {
        for (var task : tasks) {
            var recorded = attempts.getOrDefault(task.getId(), List.of());
            if (task.getStatus() == TaskStatus.RUNNING || recorded.stream().anyMatch(e -> e.getStatus() == TaskStatus.RUNNING)) return false;
            if (job.getStatus() == JobStatus.COMPLETE && task.getStatus() != TaskStatus.COMPLETE) return false;
            if (task.getStatus() == TaskStatus.PENDING && !recorded.isEmpty()) return false;
            var latest = recorded.stream().max(Comparator.comparing(TaskExecution::getStartedAt,
                    Comparator.nullsFirst(Instant::compareTo)).thenComparing(TaskExecution::getId)).orElse(null);
            if (latest != null && latest.getStatus() == TaskStatus.FAILED && latest.getRetryScheduledAt() != null
                    && task.getStatus() != TaskStatus.DEAD_LETTERED && task.getStatus() != TaskStatus.CANCELLED) return false;
        }
        return true;
    }

}
