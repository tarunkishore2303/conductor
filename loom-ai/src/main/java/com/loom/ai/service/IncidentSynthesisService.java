package com.loom.ai.service;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.util.HashSet;
import java.util.List;

@Service
public class IncidentSynthesisService {
    private static final Logger log = LoggerFactory.getLogger(IncidentSynthesisService.class);
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
        var sample = Timer.start(metrics);
        String outcome = "success";
        metrics.counter("ai.requests", "operation", "incident_synthesis").increment();
        try {
            var result = model.synthesize(sanitized);
            if (result == null || result.explanation() == null || result.explanation().isBlank() || result.explanation().length() > 2000
                    || result.citedIncidentIds() == null || result.citedIncidentIds().isEmpty() || result.citedIncidentIds().size() > 5
                    || result.citedIncidentIds().stream().anyMatch(id -> id == null || !ids.contains(id))
                    || new HashSet<>(result.citedIncidentIds()).size() != result.citedIncidentIds().size()) {
                throw new AiOutputValidationException("AI synthesis contains invalid or unsupported citations.");
            }
            return result;
        } catch (RuntimeException exception) {
            outcome = "failure";
            metrics.counter("ai.request.failures", "operation", "incident_synthesis").increment();
            throw exception;
        } finally {
            long elapsed = sample.stop(metrics.timer("ai.request.duration", "operation", "incident_synthesis", "outcome", outcome));
            log.info("AI operation=incident_synthesis model={} outcome={} durationMs={}", properties.model(), outcome, elapsed / 1_000_000);
        }
    }
}
