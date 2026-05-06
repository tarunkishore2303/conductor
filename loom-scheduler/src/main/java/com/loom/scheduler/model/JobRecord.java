package com.loom.scheduler.model;

import com.loom.common.model.JobStatus;
import com.loom.common.model.FailurePolicy;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("jobs")
public record JobRecord(
    @Id UUID id,
    String name,
    JobStatus status,
    FailurePolicy failurePolicy,
    Long version
) {}
