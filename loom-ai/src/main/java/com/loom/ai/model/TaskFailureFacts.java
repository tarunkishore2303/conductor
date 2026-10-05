package com.loom.ai.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record TaskFailureFacts(
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String taskId,
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 32) String status,
        @NotNull @Min(0) Integer maxRetries,
        @NotNull @Size(max = 300) List<@NotNull @Valid AttemptFailureFacts> attempts,
        @Size(max = 64) String deadLetteredAt) {}
