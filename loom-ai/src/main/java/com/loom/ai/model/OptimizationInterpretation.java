package com.loom.ai.model;

import java.util.List;

public record OptimizationInterpretation(String overview, List<Recommendation> recommendations) {
    public record Recommendation(List<String> candidateIds, String recommendation, String rationale) {}
}
