package com.loom.worker.service;

import com.loom.common.event.TaskEvent;

/**
 * Implement this interface to plug in real task logic.
 * The default registry falls back to NoOpTaskHandler.
 */
public interface TaskHandler {
    void execute(TaskEvent event) throws Exception;
}
