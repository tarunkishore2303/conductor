package com.loom.common.dto;

import com.loom.common.model.FailurePolicy;

import java.util.List;

public record JobSubmitRequest(
    String name,
    List<TaskDefinition> tasks,
    FailurePolicy failurePolicy
) {
    public JobSubmitRequest {
        if (failurePolicy == null) failurePolicy = FailurePolicy.FAIL_FAST;
    }
}
