package com.tarunkishore.loom_api.ai.optimization.support;

import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationCandidate;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationClassification;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationContext;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationEvidence;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationSeverity;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationType;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Shared candidate defaults and bounded evidence projection, not analyzer policy. */
public final class OptimizationCandidateFactory {
    private OptimizationCandidateFactory() {}
    public static OptimizationEvidence evidence(List<OptimizationContext.TaskRun> eligible, int excluded,
            Double rate, Long active, Double share, Long comparison, Double ratio,
            List<String> prerequisites) {
        int attempts = 0, retries = 0, retried = 0, failed = 0, terminal = 0, recovered = 0, dlq = 0;
        Map<String, Integer> errors = new TreeMap<>();
        for (var task : eligible) {
            attempts += task.attempts().size();
            int taskRetries = (int) task.attempts().stream().filter(a -> a.attemptNumber() > 0).count();
            retries += taskRetries;
            if (taskRetries > 0) retried++;
            boolean failedAttempt = task.attempts().stream().anyMatch(a -> "FAILED".equals(a.status()));
            if (failedAttempt) failed++;
            if (failedAttempt && "COMPLETE".equals(task.status())) recovered++;
            if ("FAILED".equals(task.status()) || "DEAD_LETTERED".equals(task.status())) terminal++;
            if ("DEAD_LETTERED".equals(task.status())) dlq++;
            task.attempts().forEach(a -> {
                // Do not propagate arbitrary historical error text as a metric/category.
                if ("FAILED".equals(a.status())) {
                    String code = a.errorType();
                    String bounded = code != null && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "OTHER";
                    errors.merge(bounded, 1, Integer::sum);
                }
            });
        }
        Map<String, Integer> boundedErrors = new TreeMap<>();
        errors.forEach((code, count) -> {
            if (!"OTHER".equals(code) && boundedErrors.size() < 5) boundedErrors.put(code, count);
            else boundedErrors.merge("OTHER", count, Integer::sum);
        });
        return new OptimizationEvidence(eligible.size(), excluded, attempts, retries, retried, failed,
                terminal, recovered, dlq, rate == null ? null : OptimizationMetrics.rounded(rate), active,
                active == null || attempts == 0 ? null : OptimizationMetrics.rounded((double) active / attempts),
                share == null ? null : OptimizationMetrics.rounded(share),
                comparison, ratio == null ? null : OptimizationMetrics.rounded(ratio), Collections.unmodifiableMap(boundedErrors),
                List.copyOf(prerequisites));
    }

    public static OptimizationCandidate candidate(OptimizationType type, List<String> refs, String title,
            OptimizationEvidence evidence, String limitation) {
        return new OptimizationCandidate(null, type, OptimizationSeverity.WARNING,
                OptimizationClassification.EVIDENCE_BACKED, List.copyOf(refs), title, evidence, limitation);
    }

}
