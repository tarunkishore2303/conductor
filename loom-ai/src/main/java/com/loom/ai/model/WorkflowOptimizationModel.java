package com.loom.ai.model;

public interface WorkflowOptimizationModel {
    OptimizationInterpretation interpret(WorkflowOptimizationRequest request);
}
