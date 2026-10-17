package com.tarunkishore.loom_api.ai;

import com.tarunkishore.loom_api.ai.optimization.*;
import com.tarunkishore.loom_api.config.AiServiceProperties;
import com.tarunkishore.loom_api.exception.AiOutputException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;

@Component
public class HttpWorkflowOptimizationClient implements WorkflowOptimizationClient {
    private final ObjectMapper mapper;
    private final BoundedAiJsonClient client;
    public HttpWorkflowOptimizationClient(ObjectMapper mapper, AiServiceProperties properties) {
        this.mapper = mapper;
        client = new BoundedAiJsonClient(mapper, properties.baseUrl(),
                "/internal/v1/workflows/interpret-optimization", properties.timeout());
    }
    public Result interpret(OptimizationFacts facts) {
        String json = mapper.writeValueAsString(facts);
        if (json.length() > 24000) throw new AiOutputException("Optimization evidence exceeds the context budget");
        return client.post(new Request(json, facts.workflowId(), facts.candidates().stream()
                .map(OptimizationCandidate::candidateId).toList()), Result.class);
    }
    private record Request(String factsJson, UUID workflowId, List<String> candidateIds) {}
}
