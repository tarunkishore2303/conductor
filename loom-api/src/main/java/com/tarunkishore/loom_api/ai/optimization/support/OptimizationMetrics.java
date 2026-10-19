package com.tarunkishore.loom_api.ai.optimization.support;

import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationContext;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;

/** Recorded-attempt completeness and arithmetic; never infers missing execution evidence. */
public final class OptimizationMetrics {
    private OptimizationMetrics() {}
    public static boolean completeAttempts(OptimizationContext.TaskRun task) {
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

    public static boolean completeTiming(OptimizationContext.TaskRun task) {
        return completeAttempts(task) && task.attempts().stream().allMatch(attempt ->
                attempt.startedAt() != null && attempt.completedAt() != null
                        && !attempt.completedAt().isBefore(attempt.startedAt()));
    }

    public static long activeMillis(OptimizationContext.TaskRun task) {
        return task.attempts().stream().mapToLong(attempt ->
                Duration.between(attempt.startedAt(), attempt.completedAt()).toMillis()).sum();
    }

    public static List<OptimizationContext.TaskRun> executions(OptimizationContext context, String logicalId) {
        return context.runs().stream().flatMap(run -> run.tasks().stream())
                .filter(task -> logicalId.equals(task.logicalId())).toList();
    }

    public static double rounded(double value) { return Math.round(value * 100.0) / 100.0; }
}
