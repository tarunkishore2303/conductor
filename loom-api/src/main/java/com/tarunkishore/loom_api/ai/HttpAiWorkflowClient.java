package com.tarunkishore.loom_api.ai;

import com.tarunkishore.loom_api.config.AiServiceProperties;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

@Component
public class HttpAiWorkflowClient implements AiWorkflowClient {
    private final BoundedAiJsonClient transport;

    public HttpAiWorkflowClient(
            ObjectMapper mapper,
            AiServiceProperties properties) {
        String baseUrl = properties.baseUrl();
        Duration timeout = properties.timeout();
        transport =
                new BoundedAiJsonClient(
                        mapper, baseUrl, "/internal/v1/workflows/generate", timeout);
    }

    public Generation generate(String prompt) {
        return transport.post(Map.of("prompt", prompt), Generation.class);
    }
}
