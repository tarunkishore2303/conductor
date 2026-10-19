package com.tarunkishore.loom_api.ai.optimization.domain;

import com.fasterxml.jackson.annotation.JsonAlias;

public enum OptimizationClassification { EVIDENCE_BACKED, @JsonAlias("STRUCTURAL_CANDIDATE") STRUCTURAL_OBSERVATION }
