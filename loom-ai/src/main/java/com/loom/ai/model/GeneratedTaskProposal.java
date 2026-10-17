package com.loom.ai.model;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loom.capability.NoOpTaskConfig;
import com.loom.capability.TaskConfig;

public record GeneratedTaskProposal(@JsonProperty(required = true) String identifier,
        @JsonProperty(required = true) String name,
        @JsonProperty(required = true) String type,
        @JsonProperty(required = true) List<String> dependencies,
        @JsonProperty(required = true) Integer maxRetries,
        TaskConfig config) {
    public GeneratedTaskProposal {
        if (config == null && "NOOP".equals(type)) config = new NoOpTaskConfig();
    }
    public GeneratedTaskProposal(String identifier, String name, String type, List<String> dependencies, Integer maxRetries) {
        this(identifier, name, type, dependencies, maxRetries, null);
    }
}
