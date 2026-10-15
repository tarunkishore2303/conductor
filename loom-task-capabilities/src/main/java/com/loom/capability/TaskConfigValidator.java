package com.loom.capability;
public interface TaskConfigValidator {
    TaskType type();
    void validate(TaskConfig config);
}
