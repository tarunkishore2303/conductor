package com.tarunkishore.loom_api.ai.optimization.domain;

import java.util.List;
import java.util.UUID;

/** Persisted report facts: selected history, deterministic candidates and their evidence fingerprint. */
public record OptimizationFacts(UUID workflowId, Long workflowVersion,
        List<OptimizationContext.TaskStructure> taskDefinitions, int runsConsidered,
        List<UUID> runIds, int minimumSampleSize, OptimizationOutcome outcome, boolean historyTruncated, int excludedRuns,
        long historyWindowSeconds, List<OptimizationCandidate> candidates,
        List<String> limitations, String evidenceFingerprint) {}
