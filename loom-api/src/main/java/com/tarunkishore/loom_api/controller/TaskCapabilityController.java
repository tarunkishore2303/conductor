package com.tarunkishore.loom_api.controller;

import com.loom.capability.TaskCapabilityCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Application-owned capability descriptions; discovery never executes a task. */
@RestController
public class TaskCapabilityController {
    private final TaskCapabilityCatalog catalog;
    public TaskCapabilityController(TaskCapabilityCatalog catalog) { this.catalog = catalog; }
    @GetMapping("/api/v1/task-capabilities")
    public java.util.List<TaskCapabilityCatalog.Capability> capabilities() { return catalog.capabilities(); }
}
