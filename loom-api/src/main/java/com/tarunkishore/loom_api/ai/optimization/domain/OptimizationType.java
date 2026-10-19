package com.tarunkishore.loom_api.ai.optimization.domain;

import com.fasterxml.jackson.annotation.JsonAlias;

public enum OptimizationType {
    SLOW_TASK, HIGH_RETRY_RATE, FAILURE_HOTSPOT, DLQ_HOTSPOT,
    DURATION_IMBALANCE, @JsonAlias("PARALLELISM_CANDIDATE") PARALLEL_EXECUTION_OBSERVATION
}
