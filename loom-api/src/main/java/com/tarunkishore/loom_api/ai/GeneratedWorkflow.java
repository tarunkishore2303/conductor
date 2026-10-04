package com.tarunkishore.loom_api.ai;

import java.util.List;
public record GeneratedWorkflow(String name, String description, List<Task> tasks) {
    public record Task(String identifier, String name, String type, List<String> dependencies, Integer maxRetries) {
    }
}
