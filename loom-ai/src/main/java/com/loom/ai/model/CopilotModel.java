package com.loom.ai.model;

public interface CopilotModel {
    CopilotStep step(CopilotRequest request);
}
