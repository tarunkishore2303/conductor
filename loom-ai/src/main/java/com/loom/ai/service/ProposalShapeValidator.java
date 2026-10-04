package com.loom.ai.service;

import com.loom.ai.model.GeneratedWorkflowProposal;
import org.springframework.stereotype.Component;

/** Shape checks only; the API's existing DAG validator owns graph semantics. */
@Component
public class ProposalShapeValidator {
    public void validate(GeneratedWorkflowProposal proposal) {
        if (proposal == null) fail("AI returned an empty workflow.");
        text(proposal.name(), 255, "workflow name");
        if (proposal.description() == null || proposal.description().length() > 2000) fail("Invalid workflow description.");
        if (proposal.tasks() == null || proposal.tasks().isEmpty() || proposal.tasks().size() > 50) fail("Workflow must contain between 1 and 50 tasks.");
        for (var task : proposal.tasks()) {
            if (task == null) fail("Null task.");
            text(task.identifier(), 64, "task identifier");
            if (!task.identifier().matches("[A-Za-z][A-Za-z0-9_-]*")) fail("Invalid task identifier.");
            text(task.name(), 255, "task name");
            if (!"NOOP".equals(task.type())) fail("Unsupported task type. Only NOOP is currently available.");
            if (task.maxRetries() == null || task.maxRetries() < 0 || task.maxRetries() > 5) fail("Task retries must be between 0 and 5.");
            if (task.dependencies() == null || task.dependencies().size() > 50) fail("Invalid task dependencies.");
            for (String dependency : task.dependencies()) text(dependency, 64, "dependency");
        }
    }
    private void text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) fail("Invalid " + field + ".");
    }
    private void fail(String message) { throw new AiOutputValidationException(message); }
}
