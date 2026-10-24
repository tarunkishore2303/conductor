package com.loom.capability;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Checks declared whole-output references; capability-local and cycle validation remain separate. */
public final class TaskDataflowValidator {
    public void validate(List<DataflowTask> tasks) {
        Set<String> knownIds = new HashSet<>();
        for (var task : tasks) {
            if (!knownIds.add(task.taskId())) throw new IllegalArgumentException("Duplicate dataflow task identifier");
        }
        for (var task : tasks) {
            String reference = reference(task.config());
            if (reference == null) continue;
            validateReferenceId(reference);
            if (reference.equals(task.taskId())) throw new IllegalArgumentException("Task output reference must not reference itself");
            if (!knownIds.contains(reference)) throw new IllegalArgumentException("Task output reference does not exist");
            if (task.dependsOn() == null || !task.dependsOn().contains(reference))
                throw new IllegalArgumentException("Task output reference must be a declared direct dependency");
        }
    }

    public static String reference(TaskConfig config) {
        if (config instanceof TransformTaskConfig transform) return transform.inputTaskId();
        if (config instanceof HttpRequestTaskConfig http) return http.bodyTaskId();
        return null;
    }

    static void validateReferenceId(String reference) {
        if (reference == null || !reference.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Invalid task output reference identifier");
    }

    public record DataflowTask(String taskId, List<String> dependsOn, TaskConfig config) {
        public DataflowTask {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }
    }
}
