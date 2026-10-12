package com.loom.ai.provider;

import com.loom.ai.model.ExecutionSummaryInterpretation;
import com.loom.ai.model.ExecutionSummaryModel;
import com.loom.ai.model.ExecutionSummaryRequest;
import tools.jackson.databind.json.JsonMapper;

public class OllamaExecutionSummaryModel implements ExecutionSummaryModel {
    private final OllamaStructuredOutput output;
    private final String prompt;
    private final JsonMapper json = new JsonMapper();

    public OllamaExecutionSummaryModel(OllamaStructuredOutput output, String prompt) {
        this.output = output;
        this.prompt = prompt;
    }

    @Override
    public ExecutionSummaryInterpretation summarize(ExecutionSummaryRequest request) {
        return output.call(prompt, json.writeValueAsString(request), ExecutionSummaryInterpretation.class);
    }
}
