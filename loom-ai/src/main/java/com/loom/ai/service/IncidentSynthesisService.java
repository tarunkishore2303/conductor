package com.loom.ai.service;

import com.loom.ai.validation.IncidentSynthesisValidator;

import com.loom.ai.exception.AiInvalidRequestException;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.*;
import io.micrometer.core.instrument.MeterRegistry;
import com.loom.observability.OperationMetrics;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.util.HashSet;
import java.util.List;

@Service
public class IncidentSynthesisService {
    private final IncidentSynthesisValidator outputValidator = new IncidentSynthesisValidator();
    private final IncidentSynthesisModel model;
    private final Validator validator;
    private final AiProperties properties;
    private final MeterRegistry metrics;

    public IncidentSynthesisService(IncidentSynthesisModel model, Validator validator,
                                    AiProperties properties, MeterRegistry metrics) {
        this.model = model;
        this.validator = validator;
        this.properties = properties;
        this.metrics = metrics;
    }

    public IncidentSynthesis synthesize(IncidentSynthesisRequest request) {
        if (request == null || !validator.validate(request).isEmpty()
                || request.matches().stream().anyMatch(match -> !Double.isFinite(match.similarity()))) {
            throw new AiInvalidRequestException("Invalid incident evidence.");
        }
        var ids = new HashSet<String>();
        for (var match : request.matches()) {
            if (!ids.add(match.incidentId())) throw new AiInvalidRequestException("Duplicate incident evidence.");
        }
        if (request.matches().isEmpty()) {
            return new IncidentSynthesis("No similar historical incidents were retrieved. There is no evidence to claim this failure has occurred before.", List.of());
        }
        var sanitized = new IncidentSynthesisRequest(request.currentAnalysisId(), EvidenceRedactor.redact(request.currentIncident()),
                request.matches().stream().map(match -> new IncidentMatch(match.incidentId(), match.jobId(), match.taskId(),
                        EvidenceRedactor.redact(match.documentText()), match.similarity())).toList());
        if (new JsonMapper().writeValueAsString(sanitized).length() > 32768) {
            throw new AiInvalidRequestException("Incident evidence exceeds the 32768 character limit.");
        }
        var observation = OperationMetrics.start(metrics, OperationMetrics.Operation.INCIDENT_SYNTHESIS, properties.model());
        try {
            var result = model.synthesize(sanitized);
            outputValidator.validate(result, ids);
            return result;
        } catch (RuntimeException exception) {
            observation.outcome(OperationMetrics.Outcome.FAILURE);
            throw exception;
        } finally {
            observation.close();
        }
    }
}
