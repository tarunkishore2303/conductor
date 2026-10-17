package com.tarunkishore.loom_api.ai.optimization;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class ParallelismCandidateAnalyzer implements WorkflowOptimizationAnalyzer {
    public OptimizationType type() { return OptimizationType.PARALLELISM_CANDIDATE; }
    public List<OptimizationCandidate> analyze(OptimizationContext context) {
        if (context.runs().size() < context.minSampleSize()) return List.of();
        List<OptimizationCandidate> result = new ArrayList<>();
        for (var pair : OptimizationStatistics.siblingPairs(context)) {
            var parents = context.tasks().stream().filter(task -> pair.get(0).equals(task.taskId()))
                    .findFirst().orElseThrow().dependsOn().stream().sorted().toList();
            var evidence = OptimizationStatistics.evidence(List.of(), 0, null, null, null, null, null, parents);
            result.add(new OptimizationCandidate(null, type(), OptimizationSeverity.INFO,
                    OptimizationClassification.STRUCTURAL_CANDIDATE, pair,
                    "Sibling tasks are structurally eligible to run concurrently", evidence,
                    "These tasks already share prerequisites without dependency reachability between them. Dependency structure does not prove absence of shared business-side effects or ordering requirements; no speedup or safe dependency removal is established."));
        }
        return List.copyOf(result);
    }
}
