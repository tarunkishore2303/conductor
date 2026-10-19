package com.tarunkishore.loom_api.ai.optimization.application;

import com.loom.capability.TaskConfigCodec;
import com.loom.common.dto.TaskDefinition;
import com.loom.common.model.Task;
import com.loom.common.model.TaskExecution;
import com.loom.common.model.WorkflowTemplate;
import com.tarunkishore.loom_api.ai.optimization.config.OptimizationProperties;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationContext;
import com.tarunkishore.loom_api.exception.AiOutputException;
import com.tarunkishore.loom_api.repository.FailureEvidenceRepository;
import com.tarunkishore.loom_api.repository.JobRepository;
import com.tarunkishore.loom_api.repository.TaskRepository;
import com.tarunkishore.loom_api.repository.WorkflowTemplateRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class OptimizationContextService {
    private final WorkflowTemplateRepository workflows;
    private final OptimizationHistoryLoader historyLoader;
    private final OptimizationRunMatcher runMatcher;
    private final OptimizationProperties properties;
    private final ObjectMapper mapper;

    public OptimizationContextService(WorkflowTemplateRepository workflows, JobRepository jobs,
            TaskRepository tasks, FailureEvidenceRepository evidence, OptimizationProperties properties,
            ObjectMapper mapper, TaskConfigCodec codec) {
        this.workflows = workflows;
        this.properties = properties;
        this.mapper = mapper;
        this.historyLoader = new OptimizationHistoryLoader(jobs, tasks, evidence, properties);
        this.runMatcher = new OptimizationRunMatcher(codec);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
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
        var history = historyLoader.load(workflowId);
        var notes = new ArrayList<String>();
        notes.add("Only explicitly linked future template runs are considered; historical unlinked jobs are not inferred.");
        notes.add("Durations are recorded active attempt windows including retries, not workflow wall time or critical-path contribution.");
        notes.add("Dependency structure cannot establish business-side-effect independence.");
        if (history.truncated()) notes.add("History exceeds the configured run limit; only the most recent selected runs are considered.");
        if (history.jobs().isEmpty()) return context(workflow, structures, List.of(), history.truncated(), 0, notes);
        var attemptsByTask = history.attemptsByTask();
        var runs = new ArrayList<OptimizationContext.Run>();
        int excluded = 0;
        for (var job : history.jobs()) {
            var runTasks = history.tasksByJob().getOrDefault(job.getId(), List.of());
            if (!runMatcher.matches(runTasks, byLogical, history.dependencies())
                    || !runMatcher.settled(job, runTasks, attemptsByTask)) {
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
        return context(workflow, structures, List.copyOf(runs), history.truncated(), excluded, notes);
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
