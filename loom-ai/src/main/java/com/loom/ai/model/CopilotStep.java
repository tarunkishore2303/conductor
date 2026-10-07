package com.loom.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** A proposal for one read-only tool call or an evidence-backed answer. */
public record CopilotStep(
        @JsonProperty(required = true) String action,
        String tool,
        Arguments arguments,
        String answer,
        @JsonProperty(required = true) List<String> evidenceIds) {
    public record Arguments(String taskId, Integer topK) {}
}
