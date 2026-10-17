package com.tarunkishore.loom_api.ai.optimization;

import java.util.*;
import java.util.function.Predicate;

final class RateCandidates {
    private RateCandidates() {}
    static List<OptimizationCandidate> analyze(OptimizationContext context, OptimizationType type,
            String title, Predicate<OptimizationContext.TaskRun> affected) {
        List<OptimizationCandidate> result = new ArrayList<>();
        for (var definition : context.tasks()) {
            var all = OptimizationStatistics.executions(context, definition.taskId());
            var eligible = all.stream().filter(OptimizationStatistics::completeAttempts).toList();
            long count = eligible.stream().filter(affected).count();
            if (eligible.size() < context.minSampleSize() || count < 2 || count * 100.0 / eligible.size() < 30) continue;
            result.add(OptimizationStatistics.candidate(type, List.of(definition.taskId()), title,
                    OptimizationStatistics.evidence(eligible, all.size() - eligible.size(),
                            count * 100.0 / eligible.size(), null, null, null, null, List.of()),
                    "Rates use only executions with complete contiguous recorded attempts; correlation does not establish cause."));
        }
        return List.copyOf(result);
    }
}
