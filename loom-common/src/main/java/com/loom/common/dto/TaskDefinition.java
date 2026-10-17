package com.loom.common.dto;

import java.util.List;
import com.loom.capability.TaskType;
import com.loom.capability.TaskConfig;
import com.loom.capability.NoOpTaskConfig;

/**
 * Represents one node in the submitted DAG.
 * taskId is a client-assigned logical key for dependency edges and persisted task-definition identity.
 */
public record TaskDefinition(
    String taskId,
    String name,
    List<String> dependsOn,
    int maxRetries,
    TaskType type,
    TaskConfig config
) {
    public TaskDefinition(String taskId, String name, List<String> dependsOn, int maxRetries) {
        this(taskId, name, dependsOn, maxRetries, TaskType.NOOP, new NoOpTaskConfig());
    }
    public TaskDefinition {
        if (type == null) type = TaskType.NOOP;
        if (config == null && type == TaskType.NOOP) config = new NoOpTaskConfig();
        if (dependsOn == null) dependsOn = List.of();
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
    }
}

