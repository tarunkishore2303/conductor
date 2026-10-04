package com.loom.ai.model;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

public record GeneratedWorkflowProposal(@JsonProperty(required = true) String name,
                                        @JsonProperty(required = true) String description,
                                        @JsonProperty(required = true) List<GeneratedTaskProposal> tasks) {}
