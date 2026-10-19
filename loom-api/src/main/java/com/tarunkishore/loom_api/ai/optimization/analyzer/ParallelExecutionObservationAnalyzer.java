package com.tarunkishore.loom_api.ai.optimization.analyzer;

import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationCandidate;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationClassification;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationContext;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationSeverity;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationType;
import com.tarunkishore.loom_api.ai.optimization.support.OptimizationCandidateFactory;
import com.tarunkishore.loom_api.ai.optimization.support.OptimizationGraph;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class ParallelExecutionObservationAnalyzer implements WorkflowOptimizationAnalyzer {
    public OptimizationType type() { return OptimizationType.PARALLEL_EXECUTION_OBSERVATION; }
    public List<OptimizationCandidate> analyze(OptimizationContext context) {
        if (context.runs().size() < context.minSampleSize()) return List.of();
        List<OptimizationCandidate> result = new ArrayList<>();
        for (var pair : OptimizationGraph.siblingPairs(context)) {
            var parents = context.tasks().stream().filter(task -> pair.get(0).equals(task.taskId()))
                    .findFirst().orElseThrow().dependsOn().stream().sorted().toList();
            var evidence = OptimizationCandidateFactory.evidence(List.of(), 0, null, null, null, null, null, parents);
            result.add(new OptimizationCandidate(null, type(), OptimizationSeverity.INFO,
                    OptimizationClassification.STRUCTURAL_OBSERVATION, pair,
                    "Sibling tasks are structurally eligible to run concurrently", evidence,
                    "These tasks already share prerequisites without dependency reachability between them. Dependency structure does not prove absence of shared business-side effects or ordering requirements; no speedup or safe dependency removal is established."));
        }
        return List.copyOf(result);
    }
}
