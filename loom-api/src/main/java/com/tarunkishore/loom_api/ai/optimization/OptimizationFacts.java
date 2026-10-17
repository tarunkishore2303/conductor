package com.tarunkishore.loom_api.ai.optimization;

import java.util.List;
import java.util.UUID;

public record OptimizationFacts(UUID workflowId, Long workflowVersion, int runsConsidered,
        List<UUID> runIds, int minimumSampleSize, String outcome, boolean historyTruncated, int excludedRuns,
        long historyWindowSeconds, List<OptimizationCandidate> candidates,
        List<String> limitations, String evidenceFingerprint) {}
