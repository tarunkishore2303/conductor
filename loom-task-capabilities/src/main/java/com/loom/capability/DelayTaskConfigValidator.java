package com.loom.capability;
public final class DelayTaskConfigValidator implements TaskConfigValidator {
    private final TaskProperties properties;
    public DelayTaskConfigValidator(TaskProperties properties) { this.properties = properties; }
    public TaskType type() { return TaskType.DELAY; }
    public void validate(TaskConfig config) {
        if (!(config instanceof DelayTaskConfig delay) || delay.durationMillis() < 0 || delay.durationMillis() > properties.delay().maxDuration().toMillis())
            throw new IllegalArgumentException("DELAY duration is outside allowed bounds");
    }
}
