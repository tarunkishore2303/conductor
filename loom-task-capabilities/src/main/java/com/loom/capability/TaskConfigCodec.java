package com.loom.capability;

import java.nio.charset.StandardCharsets;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** The persisted form is always decoded strictly, independently of HTTP mapper defaults. */
public final class TaskConfigCodec {
    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final TaskCapabilityValidators validators;
    public TaskConfigCodec(TaskCapabilityValidators validators) { this.validators = validators; }
    public String encode(TaskConfig config) {
        if (config == null) throw new IllegalArgumentException("Task configuration is required");
        String json = mapper.writeValueAsString(config);
        bounded(json);
        return json;
    }
    public TaskConfig decode(TaskType type, String json) {
        bounded(json);
        try {
            TaskConfig config = mapper.readValue(json, TaskConfig.class);
            validators.validate(type, config);
            return config;
        } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid task configuration"); }
    }
    private static void bounded(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > 65536) throw new IllegalArgumentException("Task configuration exceeds supported size");
    }
}
