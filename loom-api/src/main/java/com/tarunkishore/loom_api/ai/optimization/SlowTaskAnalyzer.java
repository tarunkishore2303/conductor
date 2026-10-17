package com.tarunkishore.loom_api.ai.optimization;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class SlowTaskAnalyzer implements WorkflowOptimizationAnalyzer {
    public OptimizationType type() { return OptimizationType.SLOW_TASK; }
    public List<OptimizationCandidate> analyze(OptimizationContext context) {
        if (context.tasks().size() < 2) return List.of();
        Map<String, List<OptimizationContext.TaskRun>> eligible = new TreeMap<>();
        context.tasks().forEach(task -> eligible.put(task.taskId(),
                OptimizationStatistics.executions(context, task.taskId()).stream()
                        .filter(OptimizationStatistics::completeTiming).toList()));
        long total = eligible.values().stream().flatMap(Collection::stream)
                .mapToLong(OptimizationStatistics::activeMillis).sum();
        if (total == 0 || eligible.values().stream().filter(tasks -> tasks.size() >= context.minSampleSize()).count() < 2)
            return List.of();
        List<OptimizationCandidate> result = new ArrayList<>();
        eligible.forEach((id, executions) -> {
            long active = executions.stream().mapToLong(OptimizationStatistics::activeMillis).sum();
            double share = 100.0 * active / total;
            if (executions.size() >= context.minSampleSize() && share > 50.0) {
                int excluded = OptimizationStatistics.executions(context, id).size() - executions.size();
                result.add(OptimizationStatistics.candidate(type(), List.of(id),
                        "Dominant recorded active attempt duration",
                        OptimizationStatistics.evidence(executions, excluded, null, active, share, null, null, List.of()),
                        "Share is of recorded active attempt duration across eligible task executions, not wall-clock latency or critical-path contribution."));
            }
        });
        return List.copyOf(result);
    }
}
