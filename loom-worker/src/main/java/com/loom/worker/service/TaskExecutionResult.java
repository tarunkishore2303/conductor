package com.loom.worker.service;

/** Bounded transient output only. Results are not persisted or passed between tasks. */
public record TaskExecutionResult(String output) {
    public TaskExecutionResult {
        if (output != null && output.length() > 65536) throw new IllegalArgumentException("Task output exceeds limit");
    }
    public static TaskExecutionResult completed() { return new TaskExecutionResult(null); }
}
