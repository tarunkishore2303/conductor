package com.tarunkishore.loom_api.ai.optimization.application;

import com.tarunkishore.loom_api.ai.optimization.analyzer.WorkflowOptimizationAnalyzer;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationCandidate;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationContext;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationFacts;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationOutcome;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationType;
import com.tarunkishore.loom_api.ai.optimization.support.OptimizationContextFingerprint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class OptimizationAnalyzerRegistry {
    private final List<WorkflowOptimizationAnalyzer> analyzers;
    private final OptimizationContextFingerprint fingerprints;

    public OptimizationAnalyzerRegistry(List<WorkflowOptimizationAnalyzer> supplied, OptimizationContextFingerprint fingerprints) {
        this.fingerprints = fingerprints;
        Map<OptimizationType, WorkflowOptimizationAnalyzer> registry = new EnumMap<>(OptimizationType.class);
        supplied.forEach(analyzer -> {
            if (registry.putIfAbsent(analyzer.type(), analyzer) != null)
                throw new IllegalStateException("Duplicate workflow optimization analyzer: " + analyzer.type());
        });
        analyzers = List.copyOf(registry.values());
    }

    public OptimizationFacts analyze(OptimizationContext context) {
        List<OptimizationCandidate> candidates = List.of();
        List<String> limitations = new ArrayList<>(context.limitations());
        OptimizationOutcome outcome = OptimizationOutcome.INSUFFICIENT_HISTORY;
        if (context.runs().size() >= context.minSampleSize()) {
            var sorted = analyzers.stream().flatMap(analyzer -> analyzer.analyze(context).stream())
                    .sorted(Comparator.comparing(OptimizationCandidate::type)
                            .thenComparing(candidate -> String.join("\u0000", candidate.taskReferences())))
                    .toList();
            if (sorted.size() > context.maxCandidates()) limitations.add(
                    "Candidate list truncated to configured maximum of " + context.maxCandidates() + ".");
            List<OptimizationCandidate> numbered = new ArrayList<>();
            for (var candidate : sorted.stream().limit(context.maxCandidates()).toList()) numbered.add(new OptimizationCandidate("O" + (numbered.size() + 1),
                    candidate.type(), candidate.severity(), candidate.classification(), candidate.taskReferences(),
                    candidate.title(), candidate.evidence(), candidate.limitation()));
            candidates = List.copyOf(numbered);
            outcome = candidates.isEmpty() ? OptimizationOutcome.NO_CANDIDATES : OptimizationOutcome.CANDIDATES;
        }
        var definitions = context.tasks().stream().sorted(Comparator.comparing(OptimizationContext.TaskStructure::taskId))
                .map(task -> new OptimizationContext.TaskStructure(task.taskId(), task.type(),
                        task.dependsOn().stream().sorted().toList())).toList();
        return new OptimizationFacts(context.workflowId(), context.workflowVersion(), definitions, context.runs().size(),
                context.runs().stream().map(OptimizationContext.Run::jobId).sorted().toList(),
                context.minSampleSize(), outcome, context.historyTruncated(), context.excludedRuns(),
                context.historyWindowSeconds(), candidates, limitations.stream().sorted().toList(), fingerprints.fingerprint(context));
    }

}
