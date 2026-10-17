package com.loom.ai.provider;

import com.loom.ai.model.*;
import tools.jackson.databind.json.JsonMapper;

public final class OllamaWorkflowOptimizationModel implements WorkflowOptimizationModel {
    private final OllamaStructuredOutput output;
    private final String prompt;
    private final JsonMapper json = new JsonMapper();

    public OllamaWorkflowOptimizationModel(OllamaStructuredOutput output, String prompt) {
        this.output = output;
        this.prompt = prompt;
    }

    @Override public OptimizationInterpretation interpret(WorkflowOptimizationRequest request) {
        return output.call(prompt, json.writeValueAsString(request), OptimizationInterpretation.class);
    }
}
