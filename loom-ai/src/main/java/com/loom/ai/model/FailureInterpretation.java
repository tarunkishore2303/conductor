package com.loom.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Model hypotheses only; execution facts are deliberately absent. */
public record FailureInterpretation(
        @JsonProperty(required = true) String likelyCause,
        @JsonProperty(required = true) Confidence confidence,
        @JsonProperty(required = true) String explanation,
        @JsonProperty(required = true) List<String> recommendedActions) {
    public enum Confidence { LOW, MEDIUM, HIGH }
}
