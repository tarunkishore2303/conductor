package com.loom.worker.service;

import com.loom.capability.TaskType;

/**
 * Executes one registered capability and returns a bounded result.
 * Unknown types are rejected; persistence, input resolution, retries and publication belong to the worker.
 */
public interface TaskHandler {
    TaskType type();
    TaskExecutionResult execute(TaskExecutionContext context) throws Exception;
}
