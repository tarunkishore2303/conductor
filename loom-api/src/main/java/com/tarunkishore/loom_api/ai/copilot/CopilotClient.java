package com.tarunkishore.loom_api.ai.copilot;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface CopilotClient {
    Result step(StepRequest request, Duration remaining);

    record Evidence(String evidenceId, String tool, boolean success, String errorCode,
                    String factsJson, List<CopilotToolRegistry.Reference> references) {}
    record StepRequest(String question, CopilotToolRegistry.Scope scope,
                       List<String> allowedTools, List<Evidence> evidence) {}
    record Arguments(UUID taskId, Integer topK) {}
    record Step(String action, String tool, Arguments arguments, String answer, List<String> evidenceIds) {}
    record Result(Step step, String model) {}
}
