package com.loom.ai.service;

import com.loom.ai.config.AiProperties;
import com.loom.ai.exception.AiInvalidRequestException;
import com.loom.ai.model.*;
import com.loom.ai.validation.OptimizationInterpretationValidator;
import com.loom.observability.OperationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validator;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.regex.Pattern;

@org.springframework.stereotype.Service
public class WorkflowOptimizationService {
    private static final Pattern UUID_TEXT = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private final WorkflowOptimizationModel model;
    private final Validator validator;
    private final AiProperties properties;
    private final MeterRegistry metrics;
    private final OptimizationInterpretationValidator outputValidator = new OptimizationInterpretationValidator();
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public WorkflowOptimizationService(WorkflowOptimizationModel model, Validator validator, AiProperties properties, MeterRegistry metrics) {
        this.model = model; this.validator = validator; this.properties = properties; this.metrics = metrics;
    }

    public OptimizationInterpretation interpret(WorkflowOptimizationRequest request) {
        if (request == null || !validator.validate(request).isEmpty()) invalid();
        JsonNode facts;
        try { facts = json.readTree(request.factsJson()); }
        catch (RuntimeException malformed) { throw new AiInvalidRequestException("Optimization facts must be valid JSON."); }
        if (facts == null || !facts.isObject() || !request.workflowId().equals(string(facts.get("workflowId")))) invalid();
        JsonNode candidateFacts = facts.get("candidates");
        if (candidateFacts == null || !candidateFacts.isArray() || candidateFacts.isEmpty() || candidateFacts.size() > 20) invalid();
        var candidates = new HashSet<String>();
        for (JsonNode candidate : candidateFacts) {
            String id = string(candidate.get("candidateId"));
            if (id == null || !id.matches("O[1-9][0-9]*") || !candidates.add(id)) invalid();
        }
        if (new HashSet<>(request.candidateIds()).size() != request.candidateIds().size()
                || !candidates.equals(new HashSet<>(request.candidateIds()))) invalid();
        String sanitized = json.writeValueAsString(sanitize(facts));
        if (sanitized.length() > 24000) invalid();
        var identifiers = new HashSet<String>();
        var matcher = UUID_TEXT.matcher(sanitized);
        while (matcher.find()) identifiers.add(matcher.group().toLowerCase(Locale.ROOT));
        var observation = OperationMetrics.start(metrics, OperationMetrics.Operation.OPTIMIZATION_INTERPRETATION, properties.model());
        try {
            var result = model.interpret(new WorkflowOptimizationRequest(sanitized, request.workflowId(), List.copyOf(request.candidateIds())));
            outputValidator.validate(result, candidates, identifiers);
            return result;
        } catch (RuntimeException failure) {
            observation.failure(); throw failure;
        } finally { observation.close(); }
    }

    private JsonNode sanitize(JsonNode node) {
        if (node.isString()) return json.getNodeFactory().stringNode(EvidenceRedactor.redact(node.stringValue()));
        if (node.isObject()) {
            var result = json.createObjectNode();
            node.properties().forEach(entry -> result.set(entry.getKey(), sanitize(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            var result = json.createArrayNode(); node.forEach(value -> result.add(sanitize(value))); return result;
        }
        return node;
    }
    private static String string(JsonNode node) { return node != null && node.isString() ? node.stringValue() : null; }
    private static void invalid() { throw new AiInvalidRequestException("Optimization facts and candidate references must match the requested workflow."); }
}
