package com.tarunkishore.loom_api.ai.optimization.analyzer;

import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationCandidate;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationContext;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationType;
import com.tarunkishore.loom_api.ai.optimization.support.OptimizationCandidateFactory;
import com.tarunkishore.loom_api.ai.optimization.support.OptimizationGraph;
import com.tarunkishore.loom_api.ai.optimization.support.OptimizationMetrics;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class DurationImbalanceAnalyzer implements WorkflowOptimizationAnalyzer {
    public OptimizationType type() { return OptimizationType.DURATION_IMBALANCE; }
    public List<OptimizationCandidate> analyze(OptimizationContext context) {
        List<OptimizationCandidate> result = new ArrayList<>();
        for (var pair : OptimizationGraph.siblingPairs(context)) {
            List<OptimizationContext.TaskRun> left = new ArrayList<>(), right = new ArrayList<>();
            int excluded = 0;
            for (var run : context.runs()) {
                var a = run.tasks().stream().filter(task -> pair.get(0).equals(task.logicalId())).findFirst();
                var b = run.tasks().stream().filter(task -> pair.get(1).equals(task.logicalId())).findFirst();
                if (a.isPresent() && b.isPresent() && OptimizationMetrics.completeTiming(a.get())
                        && OptimizationMetrics.completeTiming(b.get())) {
                    left.add(a.get()); right.add(b.get());
                } else excluded++;
            }
            if (left.size() < context.minSampleSize()) continue;
            long leftTime = left.stream().mapToLong(OptimizationMetrics::activeMillis).sum();
            long rightTime = right.stream().mapToLong(OptimizationMetrics::activeMillis).sum();
            long smaller = Math.min(leftTime, rightTime), larger = Math.max(leftTime, rightTime);
            // A zero millisecond aggregate cannot support a meaningful finite ratio.
            if (smaller == 0 || (double) larger / smaller < 3.0) continue;
            boolean leftSlower = leftTime > rightTime;
            result.add(OptimizationCandidateFactory.candidate(type(), leftSlower ? pair : List.of(pair.get(1), pair.get(0)),
                    "Sibling branches have unequal recorded active attempt duration",
                    OptimizationCandidateFactory.evidence(leftSlower ? left : right, excluded, null, larger, null,
                            smaller, (double) larger / smaller, List.of()),
                    "Comparison uses paired executions with complete timing. Active attempt time does not establish workflow bottlenecks or critical-path latency."));
        }
        return List.copyOf(result);
    }
}
