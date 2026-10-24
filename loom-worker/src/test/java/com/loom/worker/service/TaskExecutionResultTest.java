package com.loom.worker.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskExecutionResultTest {
    @Test void emptyResultHasNoContentType() {
        assertThat(TaskExecutionResult.completed().output()).isNull();
        assertThat(TaskExecutionResult.completed().contentType()).isNull();
    }
    @Test void byteLimitIsNotCharacterLimit() {
        assertThatThrownBy(() -> new TaskExecutionResult("\u6f22".repeat(22000), "text/plain"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("\u6f22");
    }
    @Test void maximumAsciiOutputAndEmptyOutputAreAllowed() {
        assertThat(new TaskExecutionResult("x".repeat(65536), "text/plain").output()).hasSize(65536);
        assertThat(new TaskExecutionResult("", "text/plain").output()).isEmpty();
    }
    @Test void outputTypeAndDatabaseTextMustBeValid() {
        assertThatThrownBy(() -> new TaskExecutionResult("body", "application/octet-stream")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskExecutionResult(null, "text/plain")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskExecutionResult("\0", "text/plain")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskExecutionResult("\uD800", "text/plain")).isInstanceOf(IllegalArgumentException.class);
    }
}
