package com.loom.ai.service;

import com.loom.ai.validation.ExecutionSummaryInterpretationValidator;

import com.loom.ai.exception.AiInvalidRequestException;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.ExecutionSummaryInterpretation;
import com.loom.ai.model.ExecutionSummaryModel;
import com.loom.ai.model.ExecutionSummaryRequest;
import io.micrometer.core.instrument.MeterRegistry;
import com.loom.observability.OperationMetrics;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ExecutionSummaryService {
    private static final Pattern UUID_TEXT = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private final ExecutionSummaryInterpretationValidator outputValidator = new ExecutionSummaryInterpretationValidator();
    private final ExecutionSummaryModel model;
    private final Validator validator;
    private final AiProperties properties;
    private final MeterRegistry metrics;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public ExecutionSummaryService(ExecutionSummaryModel model, Validator validator, AiProperties properties, MeterRegistry metrics) {
        this.model = model;
        this.validator = validator;
        this.properties = properties;
        this.metrics = metrics;
    }

    public ExecutionSummaryInterpretation summarize(ExecutionSummaryRequest request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new AiInvalidRequestException("Invalid execution summary request.");
        }
        JsonNode facts = parseFacts(request.factsJson());
        validateReferences(request, facts);
        String sanitizedFacts = json.writeValueAsString(sanitize(facts));
        if (sanitizedFacts.length() > 24000) throw new AiInvalidRequestException("Summary facts exceed the 24000 character limit.");
        var sanitized = new ExecutionSummaryRequest(sanitizedFacts, request.jobId(), request.taskIds(), request.failureAnalysisId());
        var observation = OperationMetrics.start(metrics, OperationMetrics.Operation.EXECUTION_SUMMARY, properties.model());
        try {
            var result = model.summarize(sanitized);
            outputValidator.validate(result, request);
            return result;
        } catch (RuntimeException exception) {
            observation.outcome(OperationMetrics.Outcome.FAILURE);
            throw exception;
        } finally {
            observation.close();
        }
    }

    private JsonNode parseFacts(String factsJson) {
        try {
            JsonNode facts = json.readTree(factsJson);
            if (facts == null || !facts.isObject()) throw new AiInvalidRequestException("Summary facts must be a JSON object.");
            return facts;
        } catch (AiInvalidRequestException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AiInvalidRequestException("Summary facts must be valid JSON.");
        }
    }

    private void validateReferences(ExecutionSummaryRequest request, JsonNode facts) {
        String status = string(facts.get("status"));
        if (!Objects.equals(request.jobId(), string(facts.get("jobId")))
                || (!"COMPLETE".equals(status) && !"FAILED".equals(status))) {
            throw new AiInvalidRequestException("Summary facts must describe the requested terminal run.");
        }
        JsonNode tasks = facts.get("slowestObservedTasks");
        if (tasks == null || !tasks.isArray()) throw new AiInvalidRequestException("Summary task references are missing.");
        var factIds = new HashSet<String>();
        for (JsonNode task : tasks) {
            String id = string(task.get("taskId"));
            if (id == null || !UUID_TEXT.matcher(id).matches() || !factIds.add(id)) {
                throw new AiInvalidRequestException("Summary task references are invalid.");
            }
        }
        JsonNode analysis = facts.get("failureAnalysisId");
        if (analysis != null && !analysis.isNull() && !analysis.isString()) {
            throw new AiInvalidRequestException("Summary failure analysis reference is invalid.");
        }
        if (new HashSet<>(request.taskIds()).size() != request.taskIds().size() || !factIds.containsAll(request.taskIds())
                || !Objects.equals(request.failureAnalysisId(), string(facts.get("failureAnalysisId")))) {
            throw new AiInvalidRequestException("Summary reference whitelist is not backed by execution facts.");
        }
    }

    private JsonNode sanitize(JsonNode node) {
        if (node.isString()) return json.getNodeFactory().stringNode(EvidenceRedactor.redact(node.stringValue()));
        if (node.isObject()) {
            var sanitized = json.createObjectNode();
            node.properties().forEach(property -> sanitized.set(property.getKey(), sanitize(property.getValue())));
            return sanitized;
        }
        if (node.isArray()) {
            var sanitized = json.createArrayNode();
            node.forEach(value -> sanitized.add(sanitize(value)));
            return sanitized;
        }
        return node;
    }

    private String string(JsonNode node) { return node != null && node.isString() ? node.stringValue() : null; }

}
