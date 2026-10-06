package com.tarunkishore.loom_api.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

@Component
public class HttpAiWorkflowClient implements AiWorkflowClient {
    private final BoundedAiJsonClient transport;

    public HttpAiWorkflowClient(
            ObjectMapper mapper,
            @Value("${conductor.ai.base-url:http://localhost:8085}") String baseUrl,
            @Value("${conductor.ai.timeout:130s}") Duration timeout) {
        transport =
                new BoundedAiJsonClient(
                        mapper, baseUrl, "/internal/v1/workflows/generate", timeout);
    }

    public Generation generate(String prompt) {
        return transport.post(Map.of("prompt", prompt), Generation.class);
    }
}
