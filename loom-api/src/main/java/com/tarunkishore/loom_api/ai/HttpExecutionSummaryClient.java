package com.tarunkishore.loom_api.ai;

import com.tarunkishore.loom_api.exception.AiOutputException;

import com.tarunkishore.loom_api.ai.summary.ExecutionSummaryClient;
import com.tarunkishore.loom_api.ai.summary.ExecutionSummaryFacts;
import com.tarunkishore.loom_api.config.AiServiceProperties;
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
            AiServiceProperties properties) {
        String baseUrl = properties.baseUrl();
        Duration timeout = properties.timeout();
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
