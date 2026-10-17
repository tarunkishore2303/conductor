package com.tarunkishore.loom_api.ai.optimization;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Component
public final class OptimizationAnalyzerRegistry {
    private final List<WorkflowOptimizationAnalyzer> analyzers;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public OptimizationAnalyzerRegistry(List<WorkflowOptimizationAnalyzer> supplied) {
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
        String outcome = "INSUFFICIENT_HISTORY";
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
            outcome = candidates.isEmpty() ? "NO_CANDIDATES" : "CANDIDATES";
        }
        var definitions = context.tasks().stream().sorted(Comparator.comparing(OptimizationContext.TaskStructure::taskId))
                .map(task -> new OptimizationContext.TaskStructure(task.taskId(), task.type(),
                        task.dependsOn().stream().sorted().toList())).toList();
        return new OptimizationFacts(context.workflowId(), context.workflowVersion(), definitions, context.runs().size(),
                context.runs().stream().map(OptimizationContext.Run::jobId).sorted().toList(),
                context.minSampleSize(), outcome, context.historyTruncated(), context.excludedRuns(),
                context.historyWindowSeconds(), candidates, limitations.stream().sorted().toList(), fingerprint(context));
    }

    private String fingerprint(OptimizationContext context) {
        // Context contains immutable execution facts only. Canonicalize all unordered collections;
        // dependency/attempt/run order cannot create a new model request.
        String canonical = mapper.writeValueAsString(canonicalize(mapper.convertValue(context, Map.class)));
        try {
            return "v1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("optimization-v1:" + canonical).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put(key.toString(), canonicalize(child)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonicalize)
                .sorted(Comparator.comparing(mapper::writeValueAsString)).toList();
        return value;
    }
}
