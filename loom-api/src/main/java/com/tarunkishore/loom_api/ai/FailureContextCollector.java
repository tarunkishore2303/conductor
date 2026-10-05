package com.tarunkishore.loom_api.ai;

import com.loom.common.model.*;
import com.loom.common.security.FailureEvidenceSanitizer;
import com.tarunkishore.loom_api.repository.*;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class FailureContextCollector {
    private final JobRepository jobs;
    private final FailureEvidenceRepository evidence;

    public FailureContextCollector(JobRepository jobs, FailureEvidenceRepository evidence) {
        this.jobs = jobs;
        this.evidence = evidence;
    }

    @Transactional(readOnly = true)
    public FailureContext collect(UUID id) {
        var job =
                jobs.findByIdWithTasks(id)
                        .orElseThrow(() -> new NoSuchElementException("Run not found"));
        if (job.getStatus() != JobStatus.FAILED)
            throw new IllegalStateException("Failure analysis requires a failed run");
        if (job.getTasks().stream().anyMatch(t -> t.getStatus() == TaskStatus.RUNNING))
            throw new IllegalStateException(
                    "Run still has running tasks; retry analysis after execution settles");
        if (job.getTasks().isEmpty() || job.getTasks().size() > 50)
            throw new AiOutputException("Failure context supports 1 to 50 tasks");
        var executions = evidence.findForJob(id, PageRequest.of(0, 301));
        if (executions.size() > 300)
            throw new AiOutputException("Run exceeds the bounded 300-attempt analysis context");
        if (executions.stream()
                .anyMatch(e -> e.getStatus() == TaskStatus.RUNNING || e.getCompletedAt() == null))
            throw new IllegalStateException("Run still has incomplete attempts");
        for (var task : job.getTasks()) {
            if (task.getStatus() != TaskStatus.PENDING && task.getStatus() != TaskStatus.FAILED) continue;
            var latest = executions.stream()
                    .filter(e -> e.getTask().getId().equals(task.getId()))
                    .reduce((previous, current) -> current);
            if (latest.isPresent() && latest.get().getRetryScheduledAt() != null) {
                throw new IllegalStateException("Run still has scheduled retries; wait for attempts to settle");
            }
        }
        if (executions.stream()
                .noneMatch(e -> e.getErrorMessage() != null && !e.getErrorMessage().isBlank()))
            throw new AiOutputException("No recorded failure evidence is available for this run");
        var unavailable = new ArrayList<String>();
        unavailable.add("Task logs are not captured; only persisted attempt errors are available.");
        unavailable.add("Historical incidents and external dependency health are not available.");
        unavailable.add("Original workflow template linkage is not recorded for jobs.");
        if (executions.stream().anyMatch(e -> e.getAttemptNumber() == null))
            unavailable.add("Attempt numbers are unavailable for legacy execution records.");
        var tasks = job.getTasks().stream()
                .sorted(Comparator.comparing(Task::getId))
                .map(task -> taskEvidence(task, executions))
                .toList();
        return new FailureContext(id, job.getStatus().name(), tasks, List.copyOf(unavailable));
    }

    private static FailureContext.TaskEvidence taskEvidence(Task task, List<TaskExecution> executions) {
        var attempts = executions.stream()
                .filter(execution -> execution.getTask().getId().equals(task.getId()))
                .map(FailureContextCollector::attemptEvidence)
                .toList();
        return new FailureContext.TaskEvidence(task.getId(), redact(task.getName(), 255),
                task.getStatus().name(), task.getMaxRetries(), attempts, task.getDeadLetteredAt());
    }

    private static FailureContext.Attempt attemptEvidence(TaskExecution execution) {
        return new FailureContext.Attempt(execution.getAttemptNumber(), execution.getStatus().name(),
                execution.getStartedAt(), execution.getCompletedAt(), redact(execution.getErrorType(), 255),
                redact(execution.getErrorMessage(), 1024), execution.getRetryScheduledAt());
    }

    static String redact(String text, int max) {
        if (text == null) return null;
        String safe = FailureEvidenceSanitizer.sanitize(text);
        return safe.substring(0, Math.min(safe.length(), max));
    }
}
