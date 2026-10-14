package com.loom.ai.service;

import com.loom.ai.validation.ProposalShapeValidator;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.WorkflowGenerationModel;
import com.loom.ai.model.GeneratedWorkflowProposal;
import io.micrometer.core.instrument.MeterRegistry;
import com.loom.observability.OperationMetrics;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class WorkflowGenerationService {
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
        var observation = OperationMetrics.start(metrics, OperationMetrics.Operation.WORKFLOW_GENERATION, properties.model());
        try {
            var result = model.generate(prompt);
            validator.validate(result);
            return result;
        } catch (RuntimeException exception) {
            observation.outcome(OperationMetrics.Outcome.FAILURE);
            throw exception;
        } finally {
            observation.close();
        }
    }
}
