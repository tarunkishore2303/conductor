package com.loom.ai.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** Read-only context supplied by the API's bounded tool loop. */
public record CopilotRequest(
        @NotBlank @Size(max = 2000) String question,
        @NotNull @Valid Scope scope,
        @NotNull @Size(max = 7) List<@NotBlank @Size(max = 64) String> allowedTools,
        @NotNull @Size(max = 4) List<@NotNull @Valid Evidence> evidence) {

    public record Scope(
            @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String jobId,
            @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String workflowId,
            @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String analysisId) {}

    public record Evidence(
            @NotBlank @Pattern(regexp = "E[1-4]") String evidenceId,
            @NotBlank @Size(max = 64) String tool,
            boolean success,
            @Size(max = 64) String errorCode,
            @NotNull @Size(max = 4096) String factsJson,
            @NotNull @Size(max = 100) List<@NotNull @Valid Reference> references) {}

    public record Reference(
            @NotBlank @Size(max = 32) String kind,
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String id,
            @NotBlank @Size(max = 255) @Pattern(regexp = "/api/v1/[^\\s]+") String url) {}
}
