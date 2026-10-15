package com.loom.worker.service;

import com.loom.worker.exception.TaskExecutionException;

import com.loom.capability.*;
import org.springframework.stereotype.Component;

@Component
public final class DelayTaskHandler implements TaskHandler {
    private final TaskProperties properties;
    public DelayTaskHandler(TaskProperties properties) { this.properties = properties; }
    @Override public TaskType type() { return TaskType.DELAY; }
    @Override public TaskExecutionResult execute(TaskExecutionContext context) throws Exception {
        if (!(context.config() instanceof DelayTaskConfig config) || config.durationMillis() < 0
                || config.durationMillis() > properties.delay().maxDuration().toMillis())
            throw new TaskExecutionException("INVALID_DELAY", "Delay duration is outside configured bounds");
        Thread.sleep(config.durationMillis());
        return TaskExecutionResult.completed();
    }
}
