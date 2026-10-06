package com.tarunkishore.loom_api.ai;

import java.util.List;

public interface FailureAnalysisClient {
    Result analyze(FailureContext context);

    record Interpretation(
            String likelyCause,
            String confidence,
            String explanation,
            List<String> recommendedActions) {}

    record Result(Interpretation interpretation, String model) {}
}
