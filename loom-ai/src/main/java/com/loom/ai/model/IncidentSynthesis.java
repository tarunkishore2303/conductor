package com.loom.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record IncidentSynthesis(@JsonProperty(required = true) String explanation,
                                @JsonProperty(required = true) List<String> citedIncidentIds) {}
