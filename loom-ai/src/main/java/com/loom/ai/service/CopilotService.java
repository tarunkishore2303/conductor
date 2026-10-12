package com.loom.ai.service;

import com.loom.ai.validation.CopilotStepValidator;

import com.loom.ai.exception.AiInvalidRequestException;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.CopilotModel;
import com.loom.ai.model.CopilotRequest;
import com.loom.ai.model.CopilotStep;
import io.micrometer.core.instrument.MeterRegistry;
import com.loom.observability.OperationMetrics;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.util.HashSet;
import java.util.Set;

/** Validates proposals; the API remains the sole owner of tool execution and scope checks. */
@Service
public class CopilotService {
    private static final Set<String> TOOLS = Set.of("getRun", "getTasks", "getFailedTasks", "getTaskAttempts",
            "getFailureAnalysis", "getSimilarIncidents", "getWorkflow");
    private final CopilotStepValidator outputValidator = new CopilotStepValidator();
    private final CopilotModel model;
    private final Validator validator;
    private final AiProperties properties;
    private final MeterRegistry metrics;
    private final JsonMapper json = new JsonMapper();

    public CopilotService(CopilotModel model, Validator validator, AiProperties properties, MeterRegistry metrics) {
        this.model = model;
        this.validator = validator;
        this.properties = properties;
        this.metrics = metrics;
    }

    public CopilotStep step(CopilotRequest request) {
        validateRequest(request);
        var sanitized = sanitize(request);
        if (!validator.validate(sanitized).isEmpty() || json.writeValueAsString(sanitized).length() > 24000) {
            throw new AiInvalidRequestException("Copilot context exceeds its permitted limits.");
        }
        var observation = OperationMetrics.start(metrics, OperationMetrics.Operation.COPILOT_STEP, properties.model());
        try {
            var result = model.step(sanitized);
            outputValidator.validate(result, sanitized);
            return result;
        } catch (RuntimeException exception) {
            observation.outcome(OperationMetrics.Outcome.FAILURE);
            throw exception;
        } finally {
            observation.close();
        }
    }

    private void validateRequest(CopilotRequest request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new AiInvalidRequestException("Invalid copilot request.");
        }
        if ((request.scope().jobId() == null) == (request.scope().workflowId() == null)
                || (request.scope().analysisId() != null && request.scope().jobId() == null)
                || !TOOLS.containsAll(request.allowedTools())
                || new HashSet<>(request.allowedTools()).size() != request.allowedTools().size()
                || request.evidence().stream().map(CopilotRequest.Evidence::evidenceId).distinct().count() != request.evidence().size()) {
            throw new AiInvalidRequestException("Invalid copilot scope, tools or evidence identifiers.");
        }
    }

    private CopilotRequest sanitize(CopilotRequest request) {
        return new CopilotRequest(EvidenceRedactor.redact(request.question()), request.scope(), request.allowedTools(),
                request.evidence().stream().map(evidence -> new CopilotRequest.Evidence(evidence.evidenceId(),
                        EvidenceRedactor.redact(evidence.tool()), evidence.success(), EvidenceRedactor.redact(evidence.errorCode()),
                        EvidenceRedactor.redact(evidence.factsJson()), evidence.references().stream().map(reference ->
                        new CopilotRequest.Reference(EvidenceRedactor.redact(reference.kind()), reference.id(),
                                EvidenceRedactor.redact(reference.url()))).toList())).toList());
    }

}
