package com.tarunkishore.loom_api.ai.optimization.client;

import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationFacts;
import java.util.List;

public interface WorkflowOptimizationClient {
    Result interpret(OptimizationFacts facts);
    record Recommendation(List<String> candidateIds, String recommendation, String rationale) {}
    record Interpretation(String overview, List<Recommendation> recommendations) {}
    record Result(Interpretation interpretation, String model) {}
}
