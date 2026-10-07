package com.loom.ai.model;

import jakarta.validation.constraints.*;
import java.util.List;

/** Contains only deterministic API facts and their explicit reference whitelist. */
public record ExecutionSummaryRequest(
        @NotBlank @Size(max = 24000) String factsJson,
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String jobId,
        @NotNull @Size(max = 50) List<@NotNull @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String> taskIds,
        @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String failureAnalysisId) {}
