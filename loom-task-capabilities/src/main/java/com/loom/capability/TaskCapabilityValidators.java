package com.loom.capability;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;

public final class TaskCapabilityValidators {
    private final Map<TaskType, TaskConfigValidator> validators;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public TaskCapabilityValidators(List<TaskConfigValidator> registered) {
        var map = new EnumMap<TaskType, TaskConfigValidator>(TaskType.class);
        for (var validator : registered) {
            if (map.putIfAbsent(validator.type(), validator) != null) throw new IllegalStateException("Duplicate task capability validator");
        }
        if (map.size() != TaskType.values().length) throw new IllegalStateException("Missing task capability validator");
        validators = Map.copyOf(map);
    }
    public void validate(TaskType type, TaskConfig config) {
        if (type == null || config == null) throw new IllegalArgumentException("Task type and configuration are required");
        validators.get(type).validate(config);
        if (mapper.writeValueAsString(config).getBytes(StandardCharsets.UTF_8).length > 65536)
            throw new IllegalArgumentException("Task configuration exceeds supported size");
    }
}
