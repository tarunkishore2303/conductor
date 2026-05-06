package com.tarunkishore.loom_api.dto;

import com.loom.common.dto.TaskDefinition;
import com.loom.common.model.FailurePolicy;
import com.loom.common.model.WorkflowTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowTemplateResponse(
    UUID id,
    String name,
    String description,
    List<TaskDefinition> tasks,
    FailurePolicy defaultFailurePolicy,
    Instant createdAt
) {
    public static WorkflowTemplateResponse from(WorkflowTemplate t, List<TaskDefinition> tasks) {
        return new WorkflowTemplateResponse(
            t.getId(), t.getName(), t.getDescription(),
            tasks, t.getDefaultFailurePolicy(), t.getCreatedAt()
        );
    }
}
