package com.loom.ai.model;

public interface WorkflowGenerationModel {
    GeneratedWorkflowProposal generate(String prompt);
}
