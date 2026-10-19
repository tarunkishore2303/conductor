package com.tarunkishore.loom_api.ai.optimization.domain;

import java.util.List;

public record OptimizationCandidate(String candidateId, OptimizationType type,
        OptimizationSeverity severity, OptimizationClassification classification,
        List<String> taskReferences, String title, OptimizationEvidence evidence, String limitation) {}
