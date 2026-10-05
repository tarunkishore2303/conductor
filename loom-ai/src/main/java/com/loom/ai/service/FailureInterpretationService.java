package com.loom.ai.service;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.FailureFactsRequest;
import com.loom.ai.model.FailureInterpretation;
import com.loom.ai.model.FailureInterpretationModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.util.UUID;

@Service
public class FailureInterpretationService {
    private static final Logger log = LoggerFactory.getLogger(FailureInterpretationService.class);
    private final FailureInterpretationModel model;
    private final FailureContextSanitizer sanitizer;
    private final Validator validator;
    private final AiProperties properties;
    private final MeterRegistry metrics;

    public FailureInterpretationService(FailureInterpretationModel model, FailureContextSanitizer sanitizer,
                                        Validator validator, AiProperties properties, MeterRegistry metrics) {
        this.model = model;
        this.sanitizer = sanitizer;
        this.validator = validator;
        this.properties = properties;
        this.metrics = metrics;
    }

    public FailureInterpretation analyze(FailureFactsRequest facts) {
        if (facts == null || !validator.validate(facts).isEmpty() || !"FAILED".equals(facts.jobStatus())) {
            throw new AiInvalidRequestException("Invalid execution facts.");
        }
        var sanitized = sanitizer.sanitize(facts);
        if (sanitized.tasks().stream().mapToInt(task -> task.attempts().size()).sum() > 300
                || new JsonMapper().writeValueAsString(sanitized).length() > 32768) {
            throw new AiInvalidRequestException("Execution context exceeds the 32768 character or 300 attempt limit.");
        }
        var sample = Timer.start(metrics);
        String correlation = UUID.randomUUID().toString();
        String outcome = "success";
        metrics.counter("ai.requests", "operation", "failure_interpretation").increment();
        try {
            var result = model.analyze(sanitized);
            validateOutput(result);
            return result;
        } catch (RuntimeException exception) {
            outcome = "failure";
            metrics.counter("ai.request.failures", "operation", "failure_interpretation").increment();
            throw exception;
        } finally {
            long duration = sample.stop(metrics.timer("ai.request.duration", "operation", "failure_interpretation", "outcome", outcome));
            log.info("AI operation=failure_interpretation correlationId={} model={} outcome={} durationMs={}",
                    correlation, properties.model(), outcome, duration / 1_000_000);
        }
    }

    private void validateOutput(FailureInterpretation result) {
        if (result == null || result.confidence() == null || !validText(result.likelyCause(), 1000)
                || !validText(result.explanation(), 4000) || result.recommendedActions() == null
                || result.recommendedActions().isEmpty() || result.recommendedActions().size() > 5
                || result.recommendedActions().stream().anyMatch(action -> !validText(action, 1000))) {
            throw new AiOutputValidationException("AI produced an invalid failure interpretation.");
        }
    }

    private boolean validText(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }
}
