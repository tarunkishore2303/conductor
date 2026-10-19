package com.tarunkishore.loom_api.ai.optimization.application;

import com.loom.common.model.Job;
import com.loom.common.model.JobStatus;
import com.loom.common.model.Task;
import com.loom.common.model.TaskExecution;
import com.tarunkishore.loom_api.ai.optimization.config.OptimizationProperties;
import com.tarunkishore.loom_api.exception.AiOutputException;
import com.tarunkishore.loom_api.repository.FailureEvidenceRepository;
import com.tarunkishore.loom_api.repository.JobRepository;
import com.tarunkishore.loom_api.repository.TaskRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;

/** Bulk-loads bounded history within the caller's repeatable-read transaction. */
final class OptimizationHistoryLoader {
    private final JobRepository jobs;
    private final TaskRepository tasks;
    private final FailureEvidenceRepository evidence;
    private final OptimizationProperties properties;

    OptimizationHistoryLoader(JobRepository jobs, TaskRepository tasks,
            FailureEvidenceRepository evidence, OptimizationProperties properties) {
        this.jobs = jobs;
        this.tasks = tasks;
        this.evidence = evidence;
        this.properties = properties;
    }

    History load(UUID workflowId) {
        var selected = jobs.findOptimizationHistory(workflowId, List.of(JobStatus.COMPLETE, JobStatus.FAILED),
                Instant.now().minus(properties.historyWindow()), PageRequest.of(0, properties.maxRuns() + 1));
        boolean truncated = selected.size() > properties.maxRuns();
        selected = selected.stream().limit(properties.maxRuns()).toList();
        if (selected.isEmpty()) {
            return new History(selected, truncated, Map.of(), Map.of(), Map.of());
        }
        var ids = selected.stream().map(Job::getId).toList();
        var taskRows = tasks.findOptimizationTasks(ids, PageRequest.of(0, properties.maxRuns() * 50 + 1));
        if (taskRows.size() > properties.maxRuns() * 50) throw new AiOutputException("Optimization task history exceeds bounds");
        var edges = tasks.findOptimizationDependencies(ids, PageRequest.of(0, properties.maxRuns() * 2500 + 1));
        if (edges.size() > properties.maxRuns() * 2500) throw new AiOutputException("Optimization dependency history exceeds bounds");
        var attempts = evidence.findOptimizationAttempts(ids, PageRequest.of(0, properties.maxAttempts() + 1));
        if (attempts.size() > properties.maxAttempts()) throw new AiOutputException("Optimization attempt history exceeds bounds");
        var taskGroups = taskRows.stream().collect(Collectors.groupingBy(t -> t.getJob().getId()));
        var attemptsByTask = attempts.stream().collect(Collectors.groupingBy(e -> e.getTask().getId()));
        var taskById = taskRows.stream().collect(Collectors.toMap(Task::getId, t -> t));
        var dependencies = new HashMap<UUID, Set<String>>();
        for (var edge : edges) {
            Task dependency = taskById.get(edge.getDependencyId());
            dependencies.computeIfAbsent(edge.getTaskId(), ignored -> new HashSet<>())
                    .add(dependency == null || dependency.getLogicalId() == null ? "<missing>" : dependency.getLogicalId());
        }
        return new History(selected, truncated, taskGroups, attemptsByTask, dependencies);
    }

    record History(List<Job> jobs, boolean truncated, Map<UUID, List<Task>> tasksByJob,
            Map<UUID, List<TaskExecution>> attemptsByTask, Map<UUID, Set<String>> dependencies) {}
}
