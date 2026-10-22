package com.loom.worker.service;

import com.loom.capability.TaskOutputLimits;

/** A successful whole-text result; the worker owns persistence and configured limits. */
public record TaskExecutionResult(String output, String contentType) {
    public TaskExecutionResult {
        TaskOutputLimits.validate(output, contentType, TaskOutputLimits.MAX_BYTES);
    }
    public static TaskExecutionResult completed() { return new TaskExecutionResult(null, null); }
}
