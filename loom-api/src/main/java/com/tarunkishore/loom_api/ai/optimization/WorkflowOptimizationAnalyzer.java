package com.tarunkishore.loom_api.ai.optimization;

import java.util.List;

public interface WorkflowOptimizationAnalyzer {
    OptimizationType type();
    List<OptimizationCandidate> analyze(OptimizationContext context);
}
