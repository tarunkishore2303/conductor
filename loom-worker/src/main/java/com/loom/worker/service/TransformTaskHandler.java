package com.loom.worker.service;

import com.loom.worker.exception.TaskExecutionException;

import com.loom.capability.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.DeserializationFeature;
import java.nio.charset.StandardCharsets;

@Component
public final class TransformTaskHandler implements TaskHandler {
    private final TaskCapabilityValidators validators;
    private final TaskProperties properties;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public TransformTaskHandler(TaskCapabilityValidators validators, TaskProperties properties) {
        this.validators = validators;
        this.properties = properties;
    }
    @Override public TaskType type() { return TaskType.TRANSFORM; }
    @Override public TaskExecutionResult execute(TaskExecutionContext context) throws TaskExecutionException {
        if (!(context.config() instanceof TransformTaskConfig config))
            throw new TaskExecutionException("INVALID_TRANSFORM_INPUT", "Transform configuration is invalid");
        try { validators.validate(type(), config); }
        catch (IllegalArgumentException invalid) { throw new TaskExecutionException("INVALID_TRANSFORM_INPUT", "Transform configuration is invalid"); }
        String source = config.input();
        if (config.inputTaskId() != null) {
            var input = context.input();
            if (input == null || !config.inputTaskId().equals(input.sourceTaskId()))
                throw new TaskExecutionException("TASK_OUTPUT_MISSING", "Declared task output is unavailable");
            if (!"application/json".equals(input.contentType()))
                throw new TaskExecutionException("INCOMPATIBLE_TASK_OUTPUT", "Transform requires application/json output");
            source = input.output();
        }
        if (source == null || source.getBytes(StandardCharsets.UTF_8).length > properties.transform().maxInputBytes())
            throw new TaskExecutionException("INVALID_TRANSFORM_INPUT", "Transform input exceeds configured bounds");
        try {
            var input = json.readTree(source);
            if (!(input instanceof ObjectNode object))
                throw new TaskExecutionException("INVALID_TRANSFORM_INPUT", "Transform input must be a JSON object");
            ObjectNode output;
            if ("JSON_SELECT".equals(config.operation())) {
                output = json.createObjectNode();
                for (String field : config.fields()) {
                    if (!object.has(field)) throw new TaskExecutionException("INVALID_TRANSFORM_INPUT", "Selected field is absent from transform input");
                    output.set(field, object.get(field));
                }
            } else if ("JSON_RENAME".equals(config.operation())) {
                output = object.deepCopy();
                for (var rename : config.renames().entrySet()) {
                    if (!object.has(rename.getKey())) throw new TaskExecutionException("INVALID_TRANSFORM_INPUT", "Rename field is absent from transform input");
                    if (!rename.getKey().equals(rename.getValue()) && object.has(rename.getValue()))
                        throw new TaskExecutionException("INVALID_TRANSFORM_INPUT", "Rename target already exists");
                }
                for (var rename : config.renames().entrySet()) output.remove(rename.getKey());
                for (var rename : config.renames().entrySet()) output.set(rename.getValue(), object.get(rename.getKey()));
            } else throw new TaskExecutionException("UNSUPPORTED_TRANSFORM", "Transform operation is unsupported");
            String result = json.writeValueAsString(output);
            if (result.getBytes(StandardCharsets.UTF_8).length > properties.output().maxBytes())
                throw new TaskExecutionException("TASK_OUTPUT_TOO_LARGE", "Transform output exceeds configured limit");
            return new TaskExecutionResult(result, "application/json");
        } catch (TaskExecutionException safe) { throw safe; }
        catch (Exception malformed) { throw new TaskExecutionException("INVALID_JSON", "Transform input is not valid bounded JSON"); }
    }
}
