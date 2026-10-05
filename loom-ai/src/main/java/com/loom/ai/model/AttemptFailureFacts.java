package com.loom.ai.model;

import jakarta.validation.constraints.*;

public record AttemptFailureFacts(
        @Min(0) Integer attemptNumber,
        @NotBlank @Size(max = 32) String status,
        @Size(max = 64) String startedAt,
        @Size(max = 64) String completedAt,
        @Size(max = 255) String errorType,
        @Size(max = 1024) String errorMessage,
        @Size(max = 64) String retryScheduledAt) {}
