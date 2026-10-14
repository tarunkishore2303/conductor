package com.loom.ai.service;

import com.loom.ai.validation.FailureInterpretationValidator;

import com.loom.ai.exception.AiInvalidRequestException;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.FailureFactsRequest;
import com.loom.ai.model.FailureInterpretation;
import com.loom.ai.model.FailureInterpretationModel;
import io.micrometer.core.instrument.MeterRegistry;
import com.loom.observability.OperationMetrics;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import com.loom.ai.config.FailureContextProperties;
import tools.jackson.databind.json.JsonMapper;
import java.util.UUID;

@Service
public class FailureInterpretationService {
    private final FailureInterpretationValidator outputValidator = new FailureInterpretationValidator();
    private final FailureInterpretationModel model;
    private final FailureContextSanitizer sanitizer;
    private final Validator validator;
    private final AiProperties properties;
    private final MeterRegistry metrics;
    private final int maxContextChars;

    public FailureInterpretationService(FailureInterpretationModel model, FailureContextSanitizer sanitizer,
                                        Validator validator, AiProperties properties, MeterRegistry metrics,
                                        FailureContextProperties contextProperties) {
        this.model = model;
        this.sanitizer = sanitizer;
        this.validator = validator;
        this.properties = properties;
        this.metrics = metrics;
        this.maxContextChars = contextProperties.failureContextMaxChars();
    }

    public FailureInterpretation analyze(FailureFactsRequest facts) {
        if (facts == null || !validator.validate(facts).isEmpty() || !"FAILED".equals(facts.jobStatus())) {
            throw new AiInvalidRequestException("Invalid execution facts.");
        }
        var sanitized = sanitizer.sanitize(facts);
        if (sanitized.tasks().stream().mapToInt(task -> task.attempts().size()).sum() > 300
                || new JsonMapper().writeValueAsString(sanitized).length() > maxContextChars) {
            throw new AiInvalidRequestException("Execution context exceeds the " + maxContextChars + " character or 300 attempt limit.");
        }
        var observation = OperationMetrics.start(metrics, OperationMetrics.Operation.FAILURE_INTERPRETATION, properties.model());
        try {
            var result = model.analyze(sanitized);
            outputValidator.validate(result);
            return result;
        } catch (RuntimeException exception) {
            observation.outcome(OperationMetrics.Outcome.FAILURE);
            throw exception;
        } finally {
            observation.close();
        }
    }

}
