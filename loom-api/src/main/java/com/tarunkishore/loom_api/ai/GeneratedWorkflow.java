package com.tarunkishore.loom_api.ai;

import com.loom.capability.NoOpTaskConfig;
import com.loom.capability.TaskConfig;
import java.util.List;

public record GeneratedWorkflow(String name, String description, List<Task> tasks) {
    public record Task(String identifier, String name, String type, List<String> dependencies,
            Integer maxRetries, TaskConfig config) {
        public Task {
            if (config == null && "NOOP".equals(type)) config = new NoOpTaskConfig();
        }
        public Task(String identifier, String name, String type, List<String> dependencies, Integer maxRetries) {
            this(identifier, name, type, dependencies, maxRetries, null);
        }
    }
}
