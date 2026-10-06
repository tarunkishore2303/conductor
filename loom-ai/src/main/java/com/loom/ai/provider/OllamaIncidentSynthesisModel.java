package com.loom.ai.provider;

import com.loom.ai.model.IncidentSynthesis;
import com.loom.ai.model.IncidentSynthesisModel;
import com.loom.ai.model.IncidentSynthesisRequest;
import tools.jackson.databind.json.JsonMapper;

public class OllamaIncidentSynthesisModel implements IncidentSynthesisModel {
    private final OllamaStructuredOutput output;
    private final String prompt;
    private final JsonMapper json = new JsonMapper();

    public OllamaIncidentSynthesisModel(OllamaStructuredOutput output, String prompt) {
        this.output = output;
        this.prompt = prompt;
    }

    @Override
    public IncidentSynthesis synthesize(IncidentSynthesisRequest request) {
        return output.call(prompt, json.writeValueAsString(request), IncidentSynthesis.class);
    }
}
