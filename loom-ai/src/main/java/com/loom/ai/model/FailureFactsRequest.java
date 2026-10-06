package com.loom.ai.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Read-only execution facts supplied by the deterministic API, never model-authored. */
public record FailureFactsRequest(
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String jobId,
        @NotBlank @Size(max = 32) String jobStatus,
        @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid TaskFailureFacts> tasks,
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 255) String> unavailableEvidence) {
    public FailureFactsRequest(String jobId, String jobStatus, List<TaskFailureFacts> tasks) {
        this(jobId, jobStatus, tasks, List.of());
    }
}
