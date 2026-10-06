package com.loom.ai.provider;

import com.loom.ai.model.FailureFactsRequest;
import com.loom.ai.model.FailureInterpretation;
import com.loom.ai.model.FailureInterpretationModel;
import tools.jackson.databind.json.JsonMapper;

public class OllamaFailureInterpretationModel implements FailureInterpretationModel {
    private final OllamaStructuredOutput output;
    private final String systemPrompt;
    private final JsonMapper json = new JsonMapper();

    public OllamaFailureInterpretationModel(OllamaStructuredOutput output, String systemPrompt) {
        this.output = output;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public FailureInterpretation analyze(FailureFactsRequest facts) {
        return output.call(systemPrompt, json.writeValueAsString(facts), FailureInterpretation.class);
    }
}
