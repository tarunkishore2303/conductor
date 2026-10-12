package com.loom.ai.provider;

import com.loom.ai.model.CopilotModel;
import com.loom.ai.model.CopilotRequest;
import com.loom.ai.model.CopilotStep;
import tools.jackson.databind.json.JsonMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OllamaCopilotModel implements CopilotModel {
    private final OllamaStructuredOutput output;
    private final String prompt;
    private final JsonMapper json = new JsonMapper();

    public OllamaCopilotModel(OllamaStructuredOutput output, String prompt) {
        this.output = output;
        this.prompt = prompt;
    }

    @Override
    public CopilotStep step(CopilotRequest request) {
        return output.call(prompt, json.writeValueAsString(request), CopilotStep.class, schema(request));
    }

    /** Request-specific native constraints complement the service's independent guardrails. */
    private String schema(CopilotRequest request) {
        var labels = request.evidence().stream().map(CopilotRequest.Evidence::evidenceId).toList();
        var citations = new LinkedHashMap<String, Object>();
        citations.put("type", "array");
        citations.put("maxItems", labels.size());
        citations.put("uniqueItems", true);
        citations.put("items", labels.isEmpty() ? Map.of("type", "string") : Map.of("type", "string", "enum", labels));
        if (request.evidence().stream().anyMatch(CopilotRequest.Evidence::success)) citations.put("minItems", 1);
        var answer = object(Map.of(
                "action", Map.of("type", "string", "enum", List.of("ANSWER")),
                "answer", Map.of("type", "string", "minLength", 1, "maxLength", 3000),
                "evidenceIds", citations), List.of("action", "answer", "evidenceIds"));
        if (request.allowedTools().isEmpty()) return json.writeValueAsString(answer);

        var arguments = object(Map.of(
                "taskId", Map.of("type", "string", "format", "uuid"),
                "topK", Map.of("type", "integer", "minimum", 1, "maximum", 5)), List.of());
        var tool = object(Map.of(
                "action", Map.of("type", "string", "enum", List.of("TOOL")),
                "tool", Map.of("type", "string", "enum", request.allowedTools()),
                "arguments", arguments,
                "evidenceIds", Map.of("type", "array", "maxItems", 0, "items", Map.of("type", "string"))),
                List.of("action", "tool", "evidenceIds"));
        return json.writeValueAsString(request.evidence().isEmpty()
                ? tool : Map.of("oneOf", List.of(tool, answer)));
    }

    private Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }
}
