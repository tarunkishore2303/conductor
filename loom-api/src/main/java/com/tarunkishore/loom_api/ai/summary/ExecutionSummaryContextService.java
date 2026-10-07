package com.tarunkishore.loom_api.ai.summary;

import com.tarunkishore.loom_api.ai.AiOutputException;
import com.tarunkishore.loom_api.ai.FailureAnalysisService;
import com.tarunkishore.loom_api.ai.copilot.CopilotReadService;
import com.tarunkishore.loom_api.ai.copilot.CopilotReadService.Attempt;
import com.tarunkishore.loom_api.ai.copilot.CopilotReadService.RunSnapshot;
import com.tarunkishore.loom_api.ai.copilot.CopilotReadService.TaskSnapshot;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class ExecutionSummaryContextService {
    private final CopilotReadService reads;
    private final FailureAnalysisService analyses;
    private final ObjectMapper mapper;

    public ExecutionSummaryContextService(CopilotReadService reads, FailureAnalysisService analyses,
            ObjectMapper mapper) {
        this.reads = reads;
        this.analyses = analyses;
        this.mapper = mapper;
    }

    public ExecutionSummaryFacts collect(UUID jobId) {
        RunSnapshot snapshot = reads.readSnapshot(jobId);
        List<TaskSnapshot> tasks = snapshot.tasks();
        if (!Set.of("COMPLETE", "FAILED").contains(snapshot.status())) {
            throw new IllegalStateException("Execution summaries require a settled COMPLETE or FAILED run");
        }
        if (tasks.isEmpty() || tasks.size() > 50) throw new AiOutputException("Summary supports 1 to 50 tasks");
        var attempts = tasks.stream().flatMap(t -> t.attempts().stream()).toList();
        if (attempts.size() > 300) throw new AiOutputException("Summary supports at most 300 attempts");
        if (tasks.stream().anyMatch(t -> "RUNNING".equals(t.status()))
                || attempts.stream().anyMatch(a -> "RUNNING".equals(a.status()))) {
            throw new IllegalStateException("Run execution is still active");
        }
        if ("COMPLETE".equals(snapshot.status()) && tasks.stream().anyMatch(t -> !"COMPLETE".equals(t.status()))) {
            throw new IllegalStateException("Completed run still contains unfinished tasks");
        }
        for (TaskSnapshot task : tasks) {
            if ("PENDING".equals(task.status()) && !task.attempts().isEmpty()) {
                throw new IllegalStateException("Pending task has execution history; retry may still be active");
            }
            Attempt latest = task.attempts().stream().max(attemptOrder()).orElse(null);
            if (latest != null && "FAILED".equals(latest.status()) && latest.retryScheduledAt() != null
                    && !"DEAD_LETTERED".equals(task.status()) && !"CANCELLED".equals(task.status())) {
                throw new IllegalStateException("Run still has a scheduled retry");
            }
        }
        var unavailable = new ArrayList<>(snapshot.unavailableEvidence());
        unavailable.add("Durations describe recorded attempts, not exact workflow start/completion or wall runtime.");
        unavailable.add("Task-duration shares use aggregate valid recorded active durations, including retries.");
        unavailable.add("Blocked-pending count means tasks left PENDING without recorded attempts; dependency blockage is not independently established.");
        var durations = new HashMap<UUID, Long>();
        boolean timingComplete = !attempts.isEmpty();
        for (TaskSnapshot task : tasks) {
            if (task.attempts().isEmpty() && !Set.of("PENDING", "CANCELLED").contains(task.status())) {
                timingComplete = false;
            }
            for (Attempt attempt : task.attempts()) {
                if (attempt.startedAt() == null || attempt.completedAt() == null
                        || attempt.completedAt().isBefore(attempt.startedAt())) {
                    timingComplete = false;
                    continue;
                }
                durations.merge(task.taskId(), Duration.between(attempt.startedAt(), attempt.completedAt()).toMillis(),
                        Long::sum);
            }
        }
        if (!timingComplete) unavailable.add("Missing or negative attempt timestamps prevent complete timing evidence.");
        Instant first = attempts.stream().map(Attempt::startedAt).filter(java.util.Objects::nonNull)
                .min(Instant::compareTo).orElse(null);
        Instant last = attempts.stream().map(Attempt::completedAt).filter(java.util.Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
        Long span = !timingComplete || first == null || last == null || last.isBefore(first)
                ? null : Duration.between(first, last).toMillis();
        long totalDuration = durations.values().stream().mapToLong(Long::longValue).sum();
        var slow = tasks.stream().filter(t -> durations.containsKey(t.taskId()))
                .sorted(Comparator.<TaskSnapshot>comparingLong(t -> durations.get(t.taskId())).reversed()
                        .thenComparing(TaskSnapshot::taskId)).limit(5)
                .map(t -> new ExecutionSummaryFacts.SlowTask(t.taskId(), t.name(), durations.get(t.taskId()),
                        totalDuration == 0 ? null : (double) durations.get(t.taskId()) / totalDuration)).toList();
        long retries = attempts.stream().filter(a -> a.attemptNumber() != null && a.attemptNumber() > 0).count();
        boolean retryComplete = completeRetryHistory(tasks);
        if (!retryComplete) unavailable.add("Legacy, missing or gapped attempt history prevents an exact retry total.");
        UUID failureAnalysisId = null;
        if ("FAILED".equals(snapshot.status())) {
            try { failureAnalysisId = analyses.get(jobId).analysisId(); }
            catch (NoSuchElementException absent) {
                unavailable.add("No stored failure analysis is available; summary collection does not generate one.");
            }
        }
        return new ExecutionSummaryFacts(snapshot.jobId(), snapshot.name(), snapshot.status(), snapshot.createdAt(),
                first, last, span, tasks.size(), count(tasks, "COMPLETE"), count(tasks, "FAILED"),
                count(tasks, "DEAD_LETTERED"), count(tasks, "CANCELLED"), count(tasks, "PENDING"),
                attempts.size(), retries, retryComplete, totalDuration, timingComplete, slow,
                failureAnalysisId, fingerprint(snapshot, failureAnalysisId), List.copyOf(unavailable));
    }

    private static int count(List<TaskSnapshot> tasks, String status) {
        return (int) tasks.stream().filter(t -> status.equals(t.status())).count();
    }

    private static boolean completeRetryHistory(List<TaskSnapshot> tasks) {
        boolean any = false;
        for (TaskSnapshot task : tasks) {
            var numbers = task.attempts().stream().map(Attempt::attemptNumber).toList();
            if (numbers.isEmpty()) {
                if (!Set.of("PENDING", "CANCELLED").contains(task.status())) return false;
                continue;
            }
            any = true;
            if (numbers.stream().anyMatch(java.util.Objects::isNull)) return false;
            var sorted = numbers.stream().sorted().toList();
            for (int i = 0; i < sorted.size(); i++) if (sorted.get(i) != i) return false;
        }
        return any;
    }

    private static Comparator<Attempt> attemptOrder() {
        return Comparator.comparing(Attempt::startedAt, Comparator.nullsFirst(Instant::compareTo))
                .thenComparing(Attempt::attemptNumber, Comparator.nullsFirst(Integer::compareTo))
                .thenComparing(Attempt::completedAt, Comparator.nullsFirst(Instant::compareTo));
    }

    private String fingerprint(RunSnapshot snapshot, UUID analysisId) {
        var canonicalTasks = snapshot.tasks().stream().sorted(Comparator.comparing(TaskSnapshot::taskId))
                .map(t -> new TaskSnapshot(t.taskId(), t.name(), t.status(), t.maxRetries(), t.deadLetteredAt(),
                        t.attempts().stream().sorted(attemptOrder()
                                .thenComparing(a -> mapper.writeValueAsString(a))).toList())).toList();
        var canonical = new Canonical(snapshot.jobId(), snapshot.name(), snapshot.status(), snapshot.createdAt(),
                canonicalTasks, snapshot.unavailableEvidence().stream().sorted().toList(), analysisId);
        try {
            byte[] bytes = ("summary-v1:" + mapper.writeValueAsString(canonical)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record Canonical(UUID jobId, String name, String status, Instant createdAt,
            List<TaskSnapshot> tasks, List<String> unavailableEvidence, UUID failureAnalysisId) {}
}
