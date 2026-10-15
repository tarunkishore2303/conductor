package com.loom.capability;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

public final class TransformTaskConfigValidator implements TaskConfigValidator {
    private final TaskProperties properties;
    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public TransformTaskConfigValidator(TaskProperties properties) { this.properties = properties; }
    public TaskType type() { return TaskType.TRANSFORM; }
    public void validate(TaskConfig config) {
        if (!(config instanceof TransformTaskConfig transform)) throw new IllegalArgumentException("TRANSFORM requires transform configuration");
        if (!Set.of("JSON_SELECT", "JSON_RENAME").contains(transform.operation() == null ? "" : transform.operation())) throw new IllegalArgumentException("Unsupported transform operation");
        if (transform.input() == null || transform.input().getBytes(StandardCharsets.UTF_8).length > properties.transform().maxInputBytes()) throw new IllegalArgumentException("Transform input exceeds allowed size");
        try { if (!mapper.readTree(transform.input()).isObject()) throw new IllegalArgumentException("Transform input must be a JSON object"); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Transform input must be a valid JSON object"); }
        if (transform.fields().size() > 50 || transform.renames().size() > 50) throw new IllegalArgumentException("Transform supports at most 50 fields");
        transform.fields().forEach(TransformTaskConfigValidator::field);
        transform.renames().forEach((from, to) -> { field(from); field(to); });
        if (new HashSet<>(transform.fields()).size() != transform.fields().size() || new HashSet<>(transform.renames().values()).size() != transform.renames().size()) throw new IllegalArgumentException("Transform field names must be unique");
        if ("JSON_SELECT".equals(transform.operation()) && (transform.fields().isEmpty() || !transform.renames().isEmpty())) throw new IllegalArgumentException("JSON_SELECT requires fields only");
        if ("JSON_RENAME".equals(transform.operation()) && (transform.renames().isEmpty() || !transform.fields().isEmpty())) throw new IllegalArgumentException("JSON_RENAME requires renames only");
    }
    private static void field(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}")) throw new IllegalArgumentException("Only simple top-level field names are supported");
    }
}
