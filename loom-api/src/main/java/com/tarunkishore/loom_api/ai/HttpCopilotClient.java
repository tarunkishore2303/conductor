package com.tarunkishore.loom_api.ai;

import com.tarunkishore.loom_api.ai.copilot.CopilotClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;

@Component
public class HttpCopilotClient implements CopilotClient {
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final Duration timeout;

    public HttpCopilotClient(ObjectMapper mapper,
            @Value("${conductor.ai.base-url:http://localhost:8085}") String baseUrl,
            @Value("${conductor.ai.timeout:130s}") Duration timeout) {
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.timeout = timeout;
    }

    public Result step(StepRequest request, Duration remaining) {
        if (remaining.isNegative() || remaining.isZero()) throw new AiUnavailableException();
        return new BoundedAiJsonClient(mapper, baseUrl, "/internal/v1/copilot/step",
                remaining.compareTo(timeout) < 0 ? remaining : timeout).post(request, Result.class);
    }
}
