package com.tarunkishore.loom_api.live;

import com.loom.common.model.JobStatus;
import com.loom.common.model.TaskStatus;

import java.time.Instant;
import java.util.UUID;

/** Invalidation metadata only. Never carries names, configuration, errors or output. */
public record RunEvent(UUID eventId, UUID jobId, UUID taskId, Type type, Instant occurredAt,
                       TaskStatus taskStatus, JobStatus jobStatus, Integer retryCount) {
    public enum Type {
        JOB_STARTED, JOB_CANCELLING, JOB_COMPLETED, JOB_FAILED, JOB_CANCELLED,
        TASK_STARTED, TASK_COMPLETED, TASK_RETRY_SCHEDULED, TASK_FAILED, TASK_DEAD_LETTERED, TASK_CANCELLED
    }
}
