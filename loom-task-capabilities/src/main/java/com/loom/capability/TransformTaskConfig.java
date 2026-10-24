package com.loom.capability;
import java.util.List;
import java.util.Map;
public record TransformTaskConfig(String operation, String input, List<String> fields,
        Map<String, String> renames, String inputTaskId) implements TaskConfig {
    public TransformTaskConfig(String operation, String input, List<String> fields, Map<String, String> renames) {
        this(operation, input, fields, renames, null);
    }
    public TransformTaskConfig {
        fields = fields == null ? List.of() : List.copyOf(fields);
        renames = renames == null ? Map.of() : Map.copyOf(renames);
    }
}
