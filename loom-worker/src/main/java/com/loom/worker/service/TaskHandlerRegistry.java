package com.loom.worker.service;

import com.loom.worker.exception.TaskExecutionException;

import com.loom.capability.TaskType;
import org.springframework.stereotype.Component;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public final class TaskHandlerRegistry {
    private final Map<TaskType, TaskHandler> handlers;
    public TaskHandlerRegistry(List<TaskHandler> handlers) {
        var registered = new EnumMap<TaskType, TaskHandler>(TaskType.class);
        for (var handler : handlers) {
            if (registered.putIfAbsent(handler.type(), handler) != null)
                throw new IllegalStateException("Duplicate task handler: " + handler.type());
        }
        this.handlers = Map.copyOf(registered);
    }
    public TaskHandler requireHandler(TaskType type) throws TaskExecutionException {
        var handler = type == null ? null : handlers.get(type);
        if (handler == null) throw new TaskExecutionException("UNSUPPORTED_TASK_TYPE", "Task type has no registered handler");
        return handler;
    }
}
