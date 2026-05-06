package com.loom.common.dto;

import java.util.List;

/**
 * Represents one node in the submitted DAG.
 * taskId is a client-assigned logical key used only to express dependsOn relationships.
 */
public record TaskDefinition(
    String taskId,
    String name,
    List<String> dependsOn,
    int maxRetries
) {
    public TaskDefinition {
        if (dependsOn == null) dependsOn = List.of();
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
    }
}
    