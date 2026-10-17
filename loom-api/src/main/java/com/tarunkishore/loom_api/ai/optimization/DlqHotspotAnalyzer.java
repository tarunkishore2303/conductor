package com.tarunkishore.loom_api.ai.optimization;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class DlqHotspotAnalyzer implements WorkflowOptimizationAnalyzer {
    public OptimizationType type() { return OptimizationType.DLQ_HOTSPOT; }
    public List<OptimizationCandidate> analyze(OptimizationContext context) {
        return RateCandidates.analyze(context, type(), "Repeated dead-lettered executions", task ->
                "DEAD_LETTERED".equals(task.status()));
    }
}
