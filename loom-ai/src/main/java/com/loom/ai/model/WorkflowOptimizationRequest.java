package com.loom.ai.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Application-computed advisory candidates and their exact citation whitelist. */
public record WorkflowOptimizationRequest(
        @NotBlank @Size(max = 24000) String factsJson,
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String workflowId,
        @NotEmpty @Size(max = 20) List<@NotNull @Pattern(regexp = "O[1-9][0-9]*") String> candidateIds) {}
