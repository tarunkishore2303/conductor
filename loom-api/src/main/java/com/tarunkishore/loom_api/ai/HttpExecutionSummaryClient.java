package com.tarunkishore.loom_api.ai;

import com.tarunkishore.loom_api.ai.summary.ExecutionSummaryClient;
import com.tarunkishore.loom_api.ai.summary.ExecutionSummaryFacts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Only computed, bounded facts cross the model boundary; no execution access is exposed. */
@Component
public class HttpExecutionSummaryClient implements ExecutionSummaryClient {
    private final ObjectMapper mapper;
    private final BoundedAiJsonClient client;

    public HttpExecutionSummaryClient(ObjectMapper mapper,
            @Value("${conductor.ai.base-url:http://localhost:8085}") String baseUrl,
            @Value("${conductor.ai.timeout:130s}") Duration timeout) {
        this.mapper = mapper;
        this.client = new BoundedAiJsonClient(mapper, baseUrl, "/internal/v1/runs/summarize", timeout);
    }

    @Override
    public Result summarize(ExecutionSummaryFacts facts) {
        String json = mapper.writeValueAsString(facts);
        if (json.length() > 24000) throw new AiOutputException("Summary facts exceed the context budget");
        return client.post(new Request(json, facts.jobId(),
                facts.slowestObservedTasks().stream().map(ExecutionSummaryFacts.SlowTask::taskId).toList(),
                facts.failureAnalysisId()), Result.class);
    }

    private record Request(String factsJson, UUID jobId, List<UUID> taskIds, UUID failureAnalysisId) {}
}
