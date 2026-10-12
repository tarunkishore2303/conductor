package com.tarunkishore.loom_api.ai;

import com.tarunkishore.loom_api.config.AiServiceProperties;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

@Component
public class HttpFailureAnalysisClient implements FailureAnalysisClient {
    private final BoundedAiJsonClient transport;

    public HttpFailureAnalysisClient(
            ObjectMapper mapper,
            AiServiceProperties properties) {
        String baseUrl = properties.baseUrl();
        Duration timeout = properties.timeout();
        transport =
                new BoundedAiJsonClient(mapper, baseUrl, "/internal/v1/failures/analyze", timeout);
    }

    public Result analyze(FailureContext context) {
        return transport.post(context, Result.class);
    }
}
