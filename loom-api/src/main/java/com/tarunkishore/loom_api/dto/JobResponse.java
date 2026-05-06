package com.tarunkishore.loom_api.dto;

import com.loom.common.model.FailurePolicy;
import com.loom.common.model.Job;
import com.loom.common.model.JobStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JobResponse(
    UUID id,
    String name,
    JobStatus status,
    FailurePolicy failurePolicy,
    List<TaskResponse> tasks,
    Instant createdAt,
    Instant updatedAt
) {
    public static JobResponse from(Job job) {
        List<TaskResponse> tasks = job.getTasks().stream()
            .map(TaskResponse::from)
            .toList();
        return new JobResponse(
            job.getId(), job.getName(), job.getStatus(), job.getFailurePolicy(),
            tasks, job.getCreatedAt(), job.getUpdatedAt()
        );
    }
}
