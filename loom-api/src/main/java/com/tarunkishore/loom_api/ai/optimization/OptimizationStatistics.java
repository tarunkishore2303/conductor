package com.tarunkishore.loom_api.ai.optimization;

import java.time.Duration;
import java.util.*;

final class OptimizationStatistics {
    private OptimizationStatistics() {}

    static boolean completeAttempts(OptimizationContext.TaskRun task) {
        var attempts = task.attempts();
        if (attempts.isEmpty()) return false;
        var numbers = new HashSet<Integer>();
        for (var attempt : attempts) {
            if (attempt.attemptNumber() == null || attempt.attemptNumber() < 0
                    || !numbers.add(attempt.attemptNumber())
                    || !("COMPLETE".equals(attempt.status()) || "FAILED".equals(attempt.status()))) return false;
        }
        for (int index = 0; index < attempts.size(); index++) if (!numbers.contains(index)) return false;
        return true;
    }

    static boolean completeTiming(OptimizationContext.TaskRun task) {
        return completeAttempts(task) && task.attempts().stream().allMatch(attempt ->
                attempt.startedAt() != null && attempt.completedAt() != null
                        && !attempt.completedAt().isBefore(attempt.startedAt()));
    }

    static long activeMillis(OptimizationContext.TaskRun task) {
        return task.attempts().stream().mapToLong(attempt ->
                Duration.between(attempt.startedAt(), attempt.completedAt()).toMillis()).sum();
    }

    static List<OptimizationContext.TaskRun> executions(OptimizationContext context, String logicalId) {
        return context.runs().stream().flatMap(run -> run.tasks().stream())
                .filter(task -> logicalId.equals(task.logicalId())).toList();
    }

    static OptimizationEvidence evidence(List<OptimizationContext.TaskRun> eligible, int excluded,
            Double rate, Long active, Double share, Long comparison, Double ratio,
            List<String> prerequisites) {
        int attempts = 0, retries = 0, retried = 0, failed = 0, terminal = 0, recovered = 0, dlq = 0;
        Map<String, Integer> errors = new TreeMap<>();
        for (var task : eligible) {
            attempts += task.attempts().size();
            int taskRetries = (int) task.attempts().stream().filter(a -> a.attemptNumber() > 0).count();
            retries += taskRetries;
            if (taskRetries > 0) retried++;
            boolean failedAttempt = task.attempts().stream().anyMatch(a -> "FAILED".equals(a.status()));
            if (failedAttempt) failed++;
            if (failedAttempt && "COMPLETE".equals(task.status())) recovered++;
            if ("FAILED".equals(task.status()) || "DEAD_LETTERED".equals(task.status())) terminal++;
            if ("DEAD_LETTERED".equals(task.status())) dlq++;
            task.attempts().forEach(a -> {
                // Do not propagate arbitrary historical error text as a metric/category.
                if ("FAILED".equals(a.status())) {
                    String code = a.errorType();
                    String bounded = code != null && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "OTHER";
                    errors.merge(bounded, 1, Integer::sum);
                }
            });
        }
        Map<String, Integer> boundedErrors = new TreeMap<>();
        errors.forEach((code, count) -> {
            if (!"OTHER".equals(code) && boundedErrors.size() < 5) boundedErrors.put(code, count);
            else boundedErrors.merge("OTHER", count, Integer::sum);
        });
        return new OptimizationEvidence(eligible.size(), excluded, attempts, retries, retried, failed,
                terminal, recovered, dlq, rate == null ? null : rounded(rate), active,
                active == null || attempts == 0 ? null : rounded((double) active / attempts),
                share == null ? null : rounded(share),
                comparison, ratio == null ? null : rounded(ratio), Collections.unmodifiableMap(boundedErrors),
                List.copyOf(prerequisites));
    }

    static double rounded(double value) { return Math.round(value * 100.0) / 100.0; }

    static OptimizationCandidate candidate(OptimizationType type, List<String> refs, String title,
            OptimizationEvidence evidence, String limitation) {
        return new OptimizationCandidate(null, type, OptimizationSeverity.WARNING,
                OptimizationClassification.EVIDENCE_BACKED, List.copyOf(refs), title, evidence, limitation);
    }

    static List<List<String>> siblingPairs(OptimizationContext context) {
        var tasks = context.tasks().stream().sorted(Comparator.comparing(OptimizationContext.TaskStructure::taskId)).toList();
        Map<String, List<String>> parents = new HashMap<>();
        tasks.forEach(task -> parents.put(task.taskId(), task.dependsOn()));
        List<List<String>> pairs = new ArrayList<>();
        for (int a = 0; a < tasks.size(); a++) for (int b = a + 1; b < tasks.size(); b++) {
            var left = tasks.get(a); var right = tasks.get(b);
            if (!left.dependsOn().isEmpty() && new HashSet<>(left.dependsOn()).equals(new HashSet<>(right.dependsOn()))
                    && !reaches(left.taskId(), right.taskId(), parents, new HashSet<>())
                    && !reaches(right.taskId(), left.taskId(), parents, new HashSet<>()))
                pairs.add(List.of(left.taskId(), right.taskId()));
        }
        return pairs;
    }

    private static boolean reaches(String from, String target, Map<String, List<String>> parents, Set<String> seen) {
        if (!seen.add(from)) return false;
        return parents.getOrDefault(from, List.of()).stream()
                .anyMatch(parent -> parent.equals(target) || reaches(parent, target, parents, seen));
    }
}
