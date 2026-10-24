package com.tarunkishore.loom_api.dto;

import com.loom.common.model.FailurePolicy;
import com.loom.common.model.Job;
import com.loom.common.model.JobStatus;

import java.time.Instant;
import java.util.UUID;

/** Bounded run-list metadata, without loading task collections or exposing output. */
public record JobSummaryResponse(
    UUID id,
    String name,
    JobStatus status,
    FailurePolicy failurePolicy,
    Instant createdAt,
    Instant updatedAt
) {
    public static JobSummaryResponse from(Job job) {
        return new JobSummaryResponse(job.getId(), job.getName(), job.getStatus(),
                job.getFailurePolicy(), job.getCreatedAt(), job.getUpdatedAt());
    }
}
