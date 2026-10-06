package com.tarunkishore.loom_api.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

@Component
public class HttpIncidentAiClient implements IncidentAiClient {
    private final BoundedAiJsonClient embeddings;
    private final BoundedAiJsonClient synthesis;

    public HttpIncidentAiClient(
            ObjectMapper mapper,
            @Value("${conductor.ai.base-url:http://localhost:8085}") String url,
            @Value("${conductor.ai.timeout:130s}") Duration timeout) {
        embeddings = new BoundedAiJsonClient(mapper, url, "/internal/v1/incidents/embed", timeout);
        synthesis =
                new BoundedAiJsonClient(mapper, url, "/internal/v1/incidents/synthesize", timeout);
    }

    public Embedding embed(String text) {
        return embeddings.post(Map.of("text", text), Embedding.class);
    }

    public SynthesisResult synthesize(SynthesisRequest request) {
        return synthesis.post(request, SynthesisResult.class);
    }
}
