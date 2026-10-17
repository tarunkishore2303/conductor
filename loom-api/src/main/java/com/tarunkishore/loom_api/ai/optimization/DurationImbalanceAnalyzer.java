package com.tarunkishore.loom_api.ai.optimization;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class DurationImbalanceAnalyzer implements WorkflowOptimizationAnalyzer {
    public OptimizationType type() { return OptimizationType.DURATION_IMBALANCE; }
    public List<OptimizationCandidate> analyze(OptimizationContext context) {
        List<OptimizationCandidate> result = new ArrayList<>();
        for (var pair : OptimizationStatistics.siblingPairs(context)) {
            List<OptimizationContext.TaskRun> left = new ArrayList<>(), right = new ArrayList<>();
            int excluded = 0;
            for (var run : context.runs()) {
                var a = run.tasks().stream().filter(task -> pair.get(0).equals(task.logicalId())).findFirst();
                var b = run.tasks().stream().filter(task -> pair.get(1).equals(task.logicalId())).findFirst();
                if (a.isPresent() && b.isPresent() && OptimizationStatistics.completeTiming(a.get())
                        && OptimizationStatistics.completeTiming(b.get())) {
                    left.add(a.get()); right.add(b.get());
                } else excluded++;
            }
            if (left.size() < context.minSampleSize()) continue;
            long leftTime = left.stream().mapToLong(OptimizationStatistics::activeMillis).sum();
            long rightTime = right.stream().mapToLong(OptimizationStatistics::activeMillis).sum();
            long smaller = Math.min(leftTime, rightTime), larger = Math.max(leftTime, rightTime);
            // A zero millisecond aggregate cannot support a meaningful finite ratio.
            if (smaller == 0 || (double) larger / smaller < 3.0) continue;
            boolean leftSlower = leftTime > rightTime;
            result.add(OptimizationStatistics.candidate(type(), leftSlower ? pair : List.of(pair.get(1), pair.get(0)),
                    "Sibling branches have unequal recorded active attempt duration",
                    OptimizationStatistics.evidence(leftSlower ? left : right, excluded, null, larger, null,
                            smaller, (double) larger / smaller, List.of()),
                    "Comparison uses paired executions with complete timing. Active attempt time does not establish workflow bottlenecks or critical-path latency."));
        }
        return List.copyOf(result);
    }
}
