package com.loom.ai.service;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.WorkflowGenerationModel;
import com.loom.ai.model.GeneratedWorkflowProposal;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class WorkflowGenerationService {
    private static final Logger log = LoggerFactory.getLogger(WorkflowGenerationService.class);
    private final WorkflowGenerationModel model;
    private final ProposalShapeValidator validator;
    private final AiProperties properties;
    private final MeterRegistry metrics;
    public WorkflowGenerationService(WorkflowGenerationModel model, ProposalShapeValidator validator,
                                     AiProperties properties, MeterRegistry metrics) {
        this.model = model;
        this.validator = validator;
        this.properties = properties;
        this.metrics = metrics;
    }
    public GeneratedWorkflowProposal generate(String prompt) {
        var sample = Timer.start(metrics);
        String correlation = UUID.randomUUID().toString();
        String outcome = "success";
        metrics.counter("ai.requests", "operation", "workflow_generation").increment();
        try {
            var result = model.generate(prompt);
            validator.validate(result);
            return result;
        } catch (RuntimeException exception) {
            outcome = "failure";
            metrics.counter("ai.request.failures", "operation", "workflow_generation").increment();
            throw exception;
        } finally {
            long duration = sample.stop(metrics.timer("ai.request.duration", "operation", "workflow_generation", "outcome", outcome));
            log.info("AI operation=workflow_generation correlationId={} model={} outcome={} durationMs={}",
                    correlation, properties.model(), outcome, duration / 1_000_000);
        }
    }
}
