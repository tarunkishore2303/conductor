package com.tarunkishore.loom_api.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

@Component
public class HttpFailureAnalysisClient implements FailureAnalysisClient {
    private final BoundedAiJsonClient transport;

    public HttpFailureAnalysisClient(
            ObjectMapper mapper,
            @Value("${conductor.ai.base-url:http://localhost:8085}") String baseUrl,
            @Value("${conductor.ai.timeout:130s}") Duration timeout) {
        transport =
                new BoundedAiJsonClient(mapper, baseUrl, "/internal/v1/failures/analyze", timeout);
    }

    public Result analyze(FailureContext context) {
        return transport.post(context, Result.class);
    }
}
