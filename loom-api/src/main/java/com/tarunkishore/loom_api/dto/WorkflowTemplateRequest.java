package com.tarunkishore.loom_api.dto;

import com.loom.common.dto.TaskDefinition;
import com.loom.common.model.FailurePolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record WorkflowTemplateRequest(
    @NotBlank String name,
    String description,
    @NotEmpty List<TaskDefinition> tasks,
    FailurePolicy defaultFailurePolicy
) {
    public WorkflowTemplateRequest {
        if (defaultFailurePolicy == null) defaultFailurePolicy = FailurePolicy.FAIL_FAST;
    }
}
