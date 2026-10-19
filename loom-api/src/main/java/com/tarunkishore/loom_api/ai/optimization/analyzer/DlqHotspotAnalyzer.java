package com.tarunkishore.loom_api.ai.optimization.analyzer;

import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationCandidate;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationContext;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationType;
import com.tarunkishore.loom_api.ai.optimization.support.OptimizationCandidateFactory;
import com.tarunkishore.loom_api.ai.optimization.support.OptimizationMetrics;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class DlqHotspotAnalyzer implements WorkflowOptimizationAnalyzer {
    public OptimizationType type() { return OptimizationType.DLQ_HOTSPOT; }
    public List<OptimizationCandidate> analyze(OptimizationContext context) {
        List<OptimizationCandidate> result = new ArrayList<>();
        for (var definition : context.tasks()) {
            var all = OptimizationMetrics.executions(context, definition.taskId());
            var eligible = all.stream().filter(OptimizationMetrics::completeAttempts).toList();
            if (eligible.size() < context.minSampleSize()) continue;
            long affected = eligible.stream().filter(task -> "DEAD_LETTERED".equals(task.status())).count();
            double rate = affected * 100.0 / eligible.size();
            if (affected < 2 || rate < 30.0) continue;
            result.add(OptimizationCandidateFactory.candidate(type(), List.of(definition.taskId()), "Repeated dead-lettered executions",
                    OptimizationCandidateFactory.evidence(eligible, all.size() - eligible.size(),
                            rate, null, null, null, null, List.of()),
                    "Rates use only executions with complete contiguous recorded attempts; correlation does not establish cause."));
        }
        return List.copyOf(result);
    }
}
