package com.loom.worker.service;

import com.loom.common.event.TaskEvent;
import com.loom.capability.TaskConfig;

public record TaskExecutionContext(TaskEvent event, TaskConfig config) {}
