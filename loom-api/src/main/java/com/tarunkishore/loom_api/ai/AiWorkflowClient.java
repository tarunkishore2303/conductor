package com.tarunkishore.loom_api.ai;

public interface AiWorkflowClient {
    Generation generate(String prompt);
    record Generation(GeneratedWorkflow workflow, String model) {
    }
}
