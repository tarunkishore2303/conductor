package com.tarunkishore.loom_api.ai;

import com.tarunkishore.loom_api.dto.WorkflowTemplateResponse;
import com.tarunkishore.loom_api.repository.AiWorkflowProposalRepository;
import com.tarunkishore.loom_api.service.WorkflowService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class AiWorkflowProposalService {
    private final AiWorkflowClient client;
    private final WorkflowProposalValidator validator;
    private final AiWorkflowProposalRepository repository;
    private final WorkflowService workflows;
    private final ObjectMapper mapper;

    public AiWorkflowProposalService(AiWorkflowClient client, WorkflowProposalValidator validator,
            AiWorkflowProposalRepository repository, WorkflowService workflows, ObjectMapper mapper) {
        this.client = client;
        this.validator = validator;
        this.repository = repository;
        this.workflows = workflows;
        this.mapper = mapper;
    }

    // The external model call deliberately runs outside a database transaction.
    public Preview generate(String prompt) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 4000) {
            throw new IllegalArgumentException("Prompt must contain 1 to 4000 characters");
        }
        var result = client.generate(prompt);
        if (result == null || result.model() == null || result.model().isBlank()
                || result.model().length() > 128) {
            throw new AiOutputException("AI response metadata is invalid");
        }
        var mapped = validator.map(result.workflow());
        var errors = validator.validate(mapped);
        var proposal = new AiWorkflowProposal();
        proposal.setId(UUID.randomUUID());
        proposal.setModel(result.model());
        proposal.setCreatedAt(Instant.now());
        proposal.setExpiresAt(proposal.getCreatedAt().plus(Duration.ofHours(24)));
        proposal.setWorkflowJson(mapper.writeValueAsString(result.workflow()));
        proposal.setValidationErrorsJson(mapper.writeValueAsString(errors));
        return preview(repository.save(proposal));
    }

    @Transactional(readOnly = true)
    public Preview get(UUID id) {
        return preview(repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Proposal not found")));
    }

    @Transactional
    public WorkflowTemplateResponse approve(UUID id) {
        // Lock the proposal so concurrent approvals cannot create two templates.
        var proposal = repository.findForApproval(id)
                .orElseThrow(() -> new NoSuchElementException("Proposal not found"));
        if (proposal.getApprovedWorkflowId() != null) {
            throw new IllegalStateException("Proposal has already been approved");
        }
        if (!proposal.getExpiresAt().isAfter(Instant.now())) {
            throw new IllegalStateException("Proposal has expired");
        }
        var mapped = validator.map(readWorkflow(proposal));
        var errors = validator.validate(mapped);
        if (!errors.isEmpty()) {
            throw new AiOutputException(String.join("; ", errors));
        }
        var template = workflows.createTemplate(mapped);
        proposal.setApprovedWorkflowId(template.getId());
        return WorkflowTemplateResponse.from(template, workflows.parseTasks(template));
    }

    private GeneratedWorkflow readWorkflow(AiWorkflowProposal proposal) {
        return mapper.readValue(proposal.getWorkflowJson(), GeneratedWorkflow.class);
    }

    private Preview preview(AiWorkflowProposal proposal) {
        List<String> errors = mapper.readValue(proposal.getValidationErrorsJson(), new TypeReference<>() {});
        return new Preview(proposal.getId(), readWorkflow(proposal), new Validation(errors.isEmpty(), errors),
                proposal.getModel(), proposal.getCreatedAt(), proposal.getExpiresAt(), proposal.getApprovedWorkflowId());
    }

    public record Validation(boolean valid, List<String> errors) {}

    public record Preview(UUID proposalId, GeneratedWorkflow workflow, Validation validation,
            String model, Instant createdAt, Instant expiresAt, UUID approvedWorkflowId) {}
}
