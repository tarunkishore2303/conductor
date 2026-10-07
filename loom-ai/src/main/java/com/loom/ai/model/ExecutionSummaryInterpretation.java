package com.loom.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Advisory prose only; numerical statistics remain deterministic API facts. */
public record ExecutionSummaryInterpretation(
        @JsonProperty(required = true) String overview,
        @JsonProperty(required = true) List<String> notableEvents,
        @JsonProperty(required = true) List<String> operationalNotes) {}
