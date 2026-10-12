package com.tarunkishore.loom_api.ai.copilot;

import com.loom.common.dto.TaskDefinition;
import com.loom.common.model.Job;
import com.loom.common.model.Task;
import com.loom.common.model.TaskExecution;
import com.loom.common.model.TaskStatus;
import com.loom.common.security.FailureEvidenceSanitizer;
import com.tarunkishore.loom_api.ai.FailureAnalysisService;
import com.tarunkishore.loom_api.ai.SimilarIncidentService;
import com.tarunkishore.loom_api.repository.FailureEvidenceRepository;
import com.tarunkishore.loom_api.repository.JobRepository;
import com.tarunkishore.loom_api.repository.WorkflowTemplateRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read-only, application-owned queries. No tool can invoke orchestration or create analysis. */
@Service
public class CopilotReadService {
    private final JobRepository jobs;
    private final FailureEvidenceRepository evidence;
    private final WorkflowTemplateRepository workflows;
    private final FailureAnalysisService analyses;
    private final SimilarIncidentService incidents;
    private final ObjectMapper mapper;

    public CopilotReadService(JobRepository jobs, FailureEvidenceRepository evidence,
            WorkflowTemplateRepository workflows, FailureAnalysisService analyses,
            SimilarIncidentService incidents, ObjectMapper mapper) {
        this.jobs = jobs;
        this.evidence = evidence;
        this.workflows = workflows;
        this.analyses = analyses;
        this.incidents = incidents;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public RunFacts getRun(UUID jobId) {
        Job job = job(jobId);
        List<TaskExecution> attempts = attempts(jobId);
        long retries = attempts.stream().filter(e -> e.getAttemptNumber() != null && e.getAttemptNumber() > 0).count();
        boolean completeHistory = completeHistory(job, attempts);
        Instant first = attempts.stream().map(TaskExecution::getStartedAt)
                .filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
        Instant last = attempts.stream().map(TaskExecution::getCompletedAt)
                .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
        boolean unsettled = job.getTasks().stream().anyMatch(t ->
                t.getStatus() == TaskStatus.PENDING || t.getStatus() == TaskStatus.RUNNING)
                || attempts.stream().anyMatch(e -> e.getCompletedAt() == null);
        Long span = first == null || last == null || unsettled || last.isBefore(first)
                ? null : Duration.between(first, last).toMillis();
        var states = job.getTasks().stream().collect(Collectors.groupingBy(
                t -> t.getStatus().name(), java.util.TreeMap::new, Collectors.counting()));
        var timing = observedTiming(job, attempts);
        return new RunFacts(job.getId(), safe(job.getName()), job.getStatus().name(),
                job.getCreatedAt(), job.getUpdatedAt(), states, attempts.size(), retries, completeHistory, first, last, span,
                timing.totalMillis(), timing.slowestTasks(), timing.complete(), evidenceNotes(attempts));
    }

    @Transactional(readOnly = true)
    public RunSnapshot readSnapshot(UUID jobId) {
        Job job = job(jobId);
        var executions = attempts(jobId);
        var tasks = job.getTasks().stream().sorted(Comparator.comparing(Task::getId)).map(t ->
                new TaskSnapshot(t.getId(), safe(t.getName()), t.getStatus().name(), t.getMaxRetries(),
                        t.getDeadLetteredAt(), executions.stream().filter(e -> t.getId().equals(e.getTask().getId()))
                                .map(CopilotReadService::attemptFact).toList())).toList();
        return new RunSnapshot(job.getId(), safe(job.getName()), job.getStatus().name(),
                job.getCreatedAt(), job.getUpdatedAt(), tasks, evidenceNotes(executions));
    }

    @Transactional(readOnly = true)
    public TaskFacts getTasks(UUID jobId, boolean failedOnly) {
        Job job = job(jobId);
        var tasks = job.getTasks().stream()
                .filter(t -> !failedOnly || t.getStatus() == TaskStatus.FAILED
                        || t.getStatus() == TaskStatus.DEAD_LETTERED)
                .sorted(Comparator.comparing(Task::getId))
                .map(t -> new TaskState(t.getId(), safe(t.getName()), t.getStatus().name(),
                        t.getMaxRetries(), t.getDeadLetteredAt())).toList();
        return new TaskFacts(job.getId(), tasks);
    }

    @Transactional(readOnly = true)
    public TaskAttemptFacts getTaskAttempts(UUID jobId, UUID taskId) {
        Job job = job(jobId);
        if (taskId == null) throw new ToolReadException("INVALID_ARGUMENT", "taskId is required");
        Task task = job.getTasks().stream().filter(t -> taskId.equals(t.getId())).findFirst()
                .orElseThrow(() -> new ToolReadException("OUT_OF_SCOPE", "Task does not belong to this run"));
        var recorded = attempts(jobId).stream().filter(e -> taskId.equals(e.getTask().getId())).toList();
        var mapped = recorded.stream().map(CopilotReadService::attemptFact).toList();
        return new TaskAttemptFacts(jobId, taskId, safe(task.getName()), task.getStatus().name(),
                task.getDeadLetteredAt(), mapped, evidenceNotes(recorded));
    }

    public FailureAnalysisService.Analysis getFailureAnalysis(UUID jobId, UUID analysisId) {
        var result = analysisId == null ? analyses.get(jobId) : analyses.getById(analysisId);
        if (!jobId.equals(result.jobId())) {
            throw new ToolReadException("OUT_OF_SCOPE", "Analysis does not belong to this run");
        }
        // Existing persisted analysis exposes code-derived facts and model interpretation separately.
        return result;
    }

    public SimilarIncidentService.Retrieval getSimilarIncidents(UUID jobId, int topK) {
        if (topK < 1 || topK > 5) throw new ToolReadException("INVALID_ARGUMENT", "topK must be 1 to 5");
        return incidents.retrieve(jobId, topK);
    }

    @Transactional(readOnly = true)
    public WorkflowFacts getWorkflow(UUID workflowId) {
        var template = workflows.findById(workflowId)
                .orElseThrow(() -> new NoSuchElementException("Workflow not found"));
        List<TaskDefinition> tasks;
        if (template.getDagDefinitionJson() == null || template.getDagDefinitionJson().length() > 32768) {
            throw new ToolReadException("CONTEXT_LIMIT", "Workflow definition exceeds bounded context");
        }
        try {
            tasks = mapper.readValue(template.getDagDefinitionJson(), new TypeReference<>() {});
        } catch (tools.jackson.core.JacksonException exception) {
            throw new ToolReadException("INVALID_EVIDENCE", "Stored workflow definition is invalid");
        }
        if (tasks == null || tasks.size() > 50 || tasks.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ToolReadException("CONTEXT_LIMIT", "Workflow exceeds bounded task context");
        }
        if (tasks.stream().anyMatch(t -> t.dependsOn().size() > 50)) {
            throw new ToolReadException("CONTEXT_LIMIT", "Workflow dependencies exceed bounded context");
        }
        var sanitized = tasks.stream().map(t -> new TaskDefinition(safe(t.taskId()), safe(t.name()),
                t.dependsOn().stream().map(CopilotReadService::safe).toList(), t.maxRetries())).toList();
        return new WorkflowFacts(template.getId(), safe(template.getName()), safe(template.getDescription()),
                sanitized, "NOOP simulation only; task names do not grant executable capabilities");
    }

    private Job job(UUID id) {
        if (id == null) throw new ToolReadException("INVALID_SCOPE", "Run scope is required");
        Job job = jobs.findByIdWithTasks(id).orElseThrow(() -> new NoSuchElementException("Run not found"));
        if (job.getTasks().size() > 50) throw new ToolReadException("CONTEXT_LIMIT", "Run has more than 50 tasks");
        return job;
    }

    private List<TaskExecution> attempts(UUID jobId) {
        var attempts = evidence.findForJob(jobId, PageRequest.of(0, 301));
        if (attempts.size() > 300) throw new ToolReadException("CONTEXT_LIMIT", "Run has more than 300 attempts");
        return attempts;
    }

    private static List<String> evidenceNotes(List<TaskExecution> attempts) {
        var notes = new java.util.ArrayList<String>();
        notes.add("Recorded retries count persisted non-null attempt numbers greater than zero; missing or legacy attempts prevent an exact total.");
        notes.add("Observed attempt timestamps are not exact workflow start/completion timestamps.");
        notes.add("Task duration shares use valid recorded active attempt durations, not workflow wall time; missing or negative timings are unavailable.");
        notes.add("Task logs are not captured.");
        if (attempts.isEmpty()) notes.add("No persisted attempt evidence is available.");
        if (attempts.stream().anyMatch(e -> e.getAttemptNumber() == null)) {
            notes.add("Legacy attempt numbers are unavailable; recorded counts may be incomplete.");
        }
        return List.copyOf(notes);
    }

    private static String safe(String value) {
        if (value != null && value.length() > 2048) {
            throw new ToolReadException("CONTEXT_LIMIT", "Evidence field exceeds bounded context");
        }
        return FailureEvidenceSanitizer.sanitize(value);
    }

    private static Attempt attemptFact(TaskExecution attempt) {
        return new Attempt(attempt.getAttemptNumber(), attempt.getStatus().name(), attempt.getStartedAt(),
                attempt.getCompletedAt(), safe(attempt.getErrorType()), safe(attempt.getErrorMessage()),
                attempt.getRetryScheduledAt());
    }

    private static boolean completeHistory(Job job, List<TaskExecution> attempts) {
        if (attempts.isEmpty() || attempts.stream().anyMatch(e -> e.getAttemptNumber() == null)) return false;
        for (Task task : job.getTasks()) {
            var numbers = attempts.stream().filter(e -> task.getId().equals(e.getTask().getId()))
                    .map(TaskExecution::getAttemptNumber).sorted().toList();
            if (numbers.isEmpty() && task.getStatus() != TaskStatus.PENDING && task.getStatus() != TaskStatus.CANCELLED) {
                return false;
            }
            for (int i = 0; i < numbers.size(); i++) if (numbers.get(i) != i) return false;
        }
        return true;
    }

    private static Timing observedTiming(Job job, List<TaskExecution> attempts) {
        var durations = new java.util.HashMap<UUID, Long>();
        var validCounts = new java.util.HashMap<UUID, Integer>();
        boolean complete = !attempts.isEmpty();
        for (TaskExecution attempt : attempts) {
            if (attempt.getStartedAt() == null || attempt.getCompletedAt() == null
                    || attempt.getCompletedAt().isBefore(attempt.getStartedAt())) {
                complete = false;
                continue;
            }
            UUID taskId = attempt.getTask().getId();
            durations.merge(taskId, Duration.between(attempt.getStartedAt(), attempt.getCompletedAt()).toMillis(), Long::sum);
            validCounts.merge(taskId, 1, Integer::sum);
        }
        long total = durations.values().stream().mapToLong(Long::longValue).sum();
        var slowest = job.getTasks().stream().filter(t -> durations.containsKey(t.getId()))
                .sorted(Comparator.<Task>comparingLong(t -> durations.get(t.getId())).reversed()
                        .thenComparing(Task::getId)).limit(5)
                .map(t -> new ObservedTaskDuration(t.getId(), safe(t.getName()), durations.get(t.getId()),
                        total == 0 ? null : (double) durations.get(t.getId()) / total,
                        validCounts.get(t.getId()), attempts.stream()
                                .filter(a -> t.getId().equals(a.getTask().getId())).count() == validCounts.get(t.getId())))
                .toList();
        return new Timing(total, slowest, complete);
    }

    public record RunFacts(UUID jobId, String name, String status, Instant createdAt, Instant lastUpdatedAt,
            Map<String, Long> taskStates, int recordedAttempts, long recordedRetries, boolean retryHistoryComplete,
            Instant firstObservedAttemptAt, Instant lastObservedCompletedAttemptAt,
            Long observedAttemptSpanMillis, long aggregateRecordedTaskDurationMillis,
            List<ObservedTaskDuration> slowestObservedTasks, boolean recordedTimingComplete,
            List<String> unavailableEvidence) {}
    public record ObservedTaskDuration(UUID taskId, String name, long recordedActiveDurationMillis,
            Double shareOfAggregateRecordedTaskDuration, int validTimedAttempts, boolean recordedTimingComplete) {}
    private record Timing(long totalMillis, List<ObservedTaskDuration> slowestTasks, boolean complete) {}
    public record TaskState(UUID taskId, String name, String status, int maxRetries, Instant deadLetteredAt) {}
    public record TaskFacts(UUID jobId, List<TaskState> tasks) {}
    public record Attempt(Integer attemptNumber, String status, Instant startedAt, Instant completedAt,
            String errorType, String errorMessage, Instant retryScheduledAt) {}
    public record TaskAttemptFacts(UUID jobId, UUID taskId, String name, String status,
            Instant deadLetteredAt, List<Attempt> attempts, List<String> unavailableEvidence) {}
    public record WorkflowFacts(UUID workflowId, String name, String description,
            List<TaskDefinition> tasks, String capability) {}
    public record TaskSnapshot(UUID taskId, String name, String status, int maxRetries,
            Instant deadLetteredAt, List<Attempt> attempts) {}
    public record RunSnapshot(UUID jobId, String name, String status, Instant createdAt,
            Instant lastUpdatedAt, List<TaskSnapshot> tasks, List<String> unavailableEvidence) {}

    public static final class ToolReadException extends RuntimeException {
        private final String code;
        public ToolReadException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String code() { return code; }
    }
}
