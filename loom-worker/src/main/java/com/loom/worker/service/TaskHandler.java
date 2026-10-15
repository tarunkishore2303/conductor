package com.loom.worker.service;

import com.loom.capability.TaskType;

/**
 * Implement this interface to plug in real task logic.
 * The default registry falls back to NoOpTaskHandler.
 */
public interface TaskHandler {
    TaskType type();
    TaskExecutionResult execute(TaskExecutionContext context) throws Exception;
}
