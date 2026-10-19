package com.tarunkishore.loom_api.ai.optimization.analyzer;

import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationCandidate;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationContext;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationType;
import java.util.List;

public interface WorkflowOptimizationAnalyzer {
    OptimizationType type();
    List<OptimizationCandidate> analyze(OptimizationContext context);
}
