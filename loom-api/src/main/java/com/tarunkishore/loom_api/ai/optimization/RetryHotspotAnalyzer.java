package com.tarunkishore.loom_api.ai.optimization;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class RetryHotspotAnalyzer implements WorkflowOptimizationAnalyzer {
    public OptimizationType type() { return OptimizationType.HIGH_RETRY_RATE; }
    public List<OptimizationCandidate> analyze(OptimizationContext context) {
        return RateCandidates.analyze(context, type(), "Repeated recorded retries", task ->
                task.attempts().stream().anyMatch(a -> a.attemptNumber() > 0));
    }
}
