package com.loom.common.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record TaskEvent(
    UUID jobId,
    UUID taskId,
    String taskName,
    int maxRetries,
    int attemptNumber,
    int generation
) {
    /** Missing JSON generation and legacy callers retain generation zero. */
    public TaskEvent(UUID jobId, UUID taskId, String taskName, int maxRetries, int attemptNumber) {
        this(jobId, taskId, taskName, maxRetries, attemptNumber, 0);
    }

    // Jackson 3 rejects a missing primitive creator argument by default. A boxed
    // compatibility argument makes old persisted Kafka records explicitly generation zero.
    @JsonCreator
    public static TaskEvent fromJson(@JsonProperty("jobId") UUID jobId,
            @JsonProperty("taskId") UUID taskId, @JsonProperty("taskName") String taskName,
            @JsonProperty("maxRetries") int maxRetries, @JsonProperty("attemptNumber") int attemptNumber,
            @JsonProperty("generation") Integer generation) {
        return new TaskEvent(jobId, taskId, taskName, maxRetries, attemptNumber, generation == null ? 0 : generation);
    }
}
