package com.tarunkishore.loom_api.ai.optimization.domain;

import java.util.List;
import java.util.Map;

/** Rates are percentages of eligible task executions; duration is active attempt time, never wall-clock share. */
/** Measurements supporting one candidate; null means uncomputed, not zero. */
public record OptimizationEvidence(int executionsConsidered, int excludedExecutions,
        int attemptsConsidered, int recordedRetries, int executionsWithRetries,
        int executionsWithFailedAttempts, int terminalFailures, int recoveredExecutions,
        int deadLetteredExecutions, Double ratePercent, Long activeAttemptMillis,
        Double averageActiveAttemptMillis, Double shareOfRecordedActiveTimePercent,
        Long comparisonActiveAttemptMillis, Double durationRatio,
        Map<String, Integer> errorTypeCounts, List<String> sharedPrerequisites) {}
