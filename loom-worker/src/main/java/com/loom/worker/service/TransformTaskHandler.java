package com.loom.worker.service;

import com.loom.worker.exception.TaskExecutionException;

import com.loom.capability.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public final class TransformTaskHandler implements TaskHandler {
    private final TaskCapabilityValidators validators;
    private final JsonMapper json = JsonMapper.builder().build();
    public TransformTaskHandler(TaskCapabilityValidators validators) { this.validators = validators; }
    @Override public TaskType type() { return TaskType.TRANSFORM; }
    @Override public TaskExecutionResult execute(TaskExecutionContext context) throws TaskExecutionException {
        if (!(context.config() instanceof TransformTaskConfig config))
            throw new TaskExecutionException("INVALID_TRANSFORM_INPUT", "Transform configuration is invalid");
        try { validators.validate(type(), config); }
        catch (IllegalArgumentException invalid) { throw new TaskExecutionException("INVALID_TRANSFORM_INPUT", "Transform configuration is invalid"); }
        try {
            var input = json.readTree(config.input());
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
            return new TaskExecutionResult(json.writeValueAsString(output));
        } catch (TaskExecutionException safe) { throw safe; }
        catch (Exception malformed) { throw new TaskExecutionException("INVALID_JSON", "Transform input is not valid bounded JSON"); }
    }
}
