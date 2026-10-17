package com.loom.capability;
public final class NoOpTaskConfigValidator implements TaskConfigValidator {
    public TaskType type() { return TaskType.NOOP; }
    public void validate(TaskConfig config) {
        if (!(config instanceof NoOpTaskConfig)) throw new IllegalArgumentException("NOOP requires empty configuration");
    }
}
