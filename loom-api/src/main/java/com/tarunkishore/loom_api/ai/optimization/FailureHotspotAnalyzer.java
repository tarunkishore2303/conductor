package com.tarunkishore.loom_api.ai.optimization;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class FailureHotspotAnalyzer implements WorkflowOptimizationAnalyzer {
    public OptimizationType type() { return OptimizationType.FAILURE_HOTSPOT; }
    public List<OptimizationCandidate> analyze(OptimizationContext context) {
        return RateCandidates.analyze(context, type(), "Repeated failed attempts", task ->
                task.attempts().stream().anyMatch(a -> "FAILED".equals(a.status())));
    }
}
