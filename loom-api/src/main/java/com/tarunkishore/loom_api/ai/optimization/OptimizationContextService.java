package com.tarunkishore.loom_api.ai.optimization;

import com.loom.common.dto.TaskDefinition;
import com.loom.common.model.*;
import com.loom.capability.TaskConfigCodec;
import com.tarunkishore.loom_api.exception.AiOutputException;
import com.tarunkishore.loom_api.repository.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OptimizationContextService {
    private final WorkflowTemplateRepository workflows;
    private final JobRepository jobs;
    private final TaskRepository tasks;
    private final FailureEvidenceRepository evidence;
    private final OptimizationProperties properties;
    private final ObjectMapper mapper;
    private final TaskConfigCodec codec;

    public OptimizationContextService(WorkflowTemplateRepository workflows, JobRepository jobs,
            TaskRepository tasks, FailureEvidenceRepository evidence, OptimizationProperties properties,
            ObjectMapper mapper, TaskConfigCodec codec) {
        this.workflows = workflows; this.jobs = jobs; this.tasks = tasks; this.evidence = evidence;
        this.properties = properties; this.mapper = mapper; this.codec = codec;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public OptimizationContext collect(UUID workflowId) {
        var workflow = workflows.findById(workflowId)
                .orElseThrow(() -> new NoSuchElementException("Workflow not found"));
        if (workflow.getDagDefinitionJson() == null || workflow.getDagDefinitionJson().length() > 1_000_000)
            throw new AiOutputException("Workflow definition exceeds optimization bounds");
        List<TaskDefinition> definitions;
        try { definitions = mapper.readValue(workflow.getDagDefinitionJson(), new TypeReference<>() {}); }
        catch (RuntimeException invalid) { throw new AiOutputException("Stored workflow definition is invalid"); }
        if (definitions == null || definitions.isEmpty() || definitions.size() > 50)
            throw new AiOutputException("Optimization supports 1 to 50 task definitions");
        var byLogical = new TreeMap<String, TaskDefinition>();
        for (var definition : definitions) {
            if (definition == null || definition.taskId() == null || !definition.taskId().matches("[A-Za-z0-9_-]{1,128}")
                    || definition.dependsOn().size() > 50 || byLogical.put(definition.taskId(), definition) != null)
                throw new AiOutputException("Stored workflow task identities are invalid");
        }
        var structures = definitions.stream().sorted(Comparator.comparing(TaskDefinition::taskId))
                .map(d -> new OptimizationContext.TaskStructure(d.taskId(), d.type(),
                        d.dependsOn().stream().sorted().toList())).toList();
        var selected = jobs.findOptimizationHistory(workflowId, List.of(JobStatus.COMPLETE, JobStatus.FAILED),
                Instant.now().minus(properties.historyWindow()), PageRequest.of(0, properties.maxRuns() + 1));
        boolean truncated = selected.size() > properties.maxRuns();
        selected = selected.stream().limit(properties.maxRuns()).toList();
        var notes = new ArrayList<String>();
        notes.add("Only explicitly linked future template runs are considered; historical unlinked jobs are not inferred.");
        notes.add("Durations are recorded active attempt windows including retries, not workflow wall time or critical-path contribution.");
        notes.add("Dependency structure cannot establish business-side-effect independence.");
        if (truncated) notes.add("History exceeds the configured run limit; only the most recent selected runs are considered.");
        if (selected.isEmpty()) return context(workflow, structures, List.of(), truncated, 0, notes);
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
        var runs = new ArrayList<OptimizationContext.Run>();
        int excluded = 0;
        for (var job : selected) {
            var runTasks = taskGroups.getOrDefault(job.getId(), List.of());
            if (!matches(runTasks, byLogical, dependencies) || !settled(job, runTasks, attemptsByTask)) {
                excluded++; continue;
            }
            var facts = runTasks.stream().sorted(Comparator.comparing(Task::getLogicalId)).map(t ->
                    new OptimizationContext.TaskRun(t.getId(), t.getLogicalId(), t.getStatus().name(),
                            t.getDeadLetteredAt(), attemptsByTask.getOrDefault(t.getId(), List.of()).stream()
                            .sorted(Comparator.comparing(TaskExecution::getAttemptNumber, Comparator.nullsFirst(Integer::compareTo))
                                    .thenComparing(TaskExecution::getStartedAt, Comparator.nullsFirst(Instant::compareTo))
                                    .thenComparing(TaskExecution::getId))
                            .map(e -> new OptimizationContext.Attempt(e.getAttemptNumber(), e.getStatus().name(),
                                    e.getStartedAt(), e.getCompletedAt(), safeErrorType(e.getErrorType()), e.getRetryScheduledAt())).toList())).toList();
            runs.add(new OptimizationContext.Run(job.getId(), job.getStatus().name(), job.getCreatedAt(), facts));
        }
        if (excluded > 0) notes.add("Unsettled runs or runs with missing/drifted task definitions were excluded.");
        notes.add("Missing, duplicate or gapped attempt numbers make retry history incomplete; invalid timestamps make timing incomplete.");
        runs.sort(Comparator.comparing(OptimizationContext.Run::jobId));
        return context(workflow, structures, List.copyOf(runs), truncated, excluded, notes);
    }

    private boolean matches(List<Task> rows, Map<String, TaskDefinition> definitions, Map<UUID, Set<String>> dependencies) {
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

    private static boolean settled(Job job, List<Task> tasks, Map<UUID, List<TaskExecution>> attempts) {
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

    private OptimizationContext context(WorkflowTemplate workflow, List<OptimizationContext.TaskStructure> tasks,
            List<OptimizationContext.Run> runs, boolean truncated, int excluded, List<String> notes) {
        return new OptimizationContext(workflow.getId(), workflow.getVersion(), tasks, runs, truncated, excluded,
                properties.minSampleSize(), properties.maxCandidates(), properties.historyWindow().toSeconds(), List.copyOf(notes));
    }

    private static String safeErrorType(String value) {
        if (value == null) return null;
        // Types are application-controlled codes/class names, never raw exception text or network details.
        return value.length() <= 120 && value.matches("[A-Za-z0-9_.$]+") ? value : "UNCLASSIFIED_ERROR";
    }
}
