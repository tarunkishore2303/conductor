package com.loom.ai.model;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

public record GeneratedTaskProposal(@JsonProperty(required = true) String identifier,
                                    @JsonProperty(required = true) String name,
                                    @JsonProperty(required = true) String type,
                                    @JsonProperty(required = true) List<String> dependencies,
                                    @JsonProperty(required = true) Integer maxRetries) {}
