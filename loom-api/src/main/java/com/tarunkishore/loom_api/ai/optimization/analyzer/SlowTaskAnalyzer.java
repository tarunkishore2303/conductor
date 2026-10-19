package com.tarunkishore.loom_api.ai.optimization.analyzer;

import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationCandidate;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationContext;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationType;
import com.tarunkishore.loom_api.ai.optimization.support.OptimizationCandidateFactory;
import com.tarunkishore.loom_api.ai.optimization.support.OptimizationMetrics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public final class SlowTaskAnalyzer implements WorkflowOptimizationAnalyzer {
    public OptimizationType type() { return OptimizationType.SLOW_TASK; }
    public List<OptimizationCandidate> analyze(OptimizationContext context) {
        if (context.tasks().size() < 2) return List.of();
        Map<String, List<OptimizationContext.TaskRun>> eligible = new TreeMap<>();
        context.tasks().forEach(task -> eligible.put(task.taskId(),
                OptimizationMetrics.executions(context, task.taskId()).stream()
                        .filter(OptimizationMetrics::completeTiming).toList()));
        // Denominator includes ALL complete timed executions, even sparse definitions.
        // Minimum samples gate advice, not what counts as recorded active time.
        long total = eligible.values().stream().flatMap(Collection::stream)
                .mapToLong(OptimizationMetrics::activeMillis).sum();
        if (total == 0 || eligible.values().stream().filter(tasks -> tasks.size() >= context.minSampleSize()).count() < 2)
            return List.of();
        List<OptimizationCandidate> result = new ArrayList<>();
        eligible.forEach((id, executions) -> {
            long active = executions.stream().mapToLong(OptimizationMetrics::activeMillis).sum();
            double share = 100.0 * active / total;
            if (executions.size() >= context.minSampleSize() && share > 50.0) {
                int excluded = OptimizationMetrics.executions(context, id).size() - executions.size();
                result.add(OptimizationCandidateFactory.candidate(type(), List.of(id),
                        "Dominant recorded active attempt duration",
                        OptimizationCandidateFactory.evidence(executions, excluded, null, active, share, null, null, List.of()),
                        "Share is of recorded active attempt duration across eligible task executions, not wall-clock latency or critical-path contribution."));
            }
        });
        return List.copyOf(result);
    }
}
