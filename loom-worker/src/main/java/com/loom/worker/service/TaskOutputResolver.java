package com.loom.worker.service;

import com.loom.capability.HttpRequestTaskConfig;
import com.loom.capability.TaskConfig;
import com.loom.capability.TaskOutputLimits;
import com.loom.capability.TaskProperties;
import com.loom.capability.TransformTaskConfig;
import com.loom.common.model.Task;
import com.loom.common.model.TaskStatus;
import com.loom.worker.exception.TaskExecutionException;
import com.loom.worker.repository.TaskExecutionJpaRepository;
import com.loom.worker.repository.WorkerTaskRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** Resolves one whole output, using persisted same-job direct dependencies rather than event payloads. */
@Component
public final class TaskOutputResolver {
    private final WorkerTaskRepository tasks;
    private final TaskExecutionJpaRepository executions;
    private final TaskProperties properties;

    public TaskOutputResolver(WorkerTaskRepository tasks, TaskExecutionJpaRepository executions,
            TaskProperties properties) {
        this.tasks = tasks;
        this.executions = executions;
        this.properties = properties;
    }

    public ResolvedTaskInput resolve(Task task, TaskConfig config) throws TaskExecutionException {
        String reference = reference(config);
        if (reference == null) return null;
        if (reference.equals(task.getLogicalId()))
            throw failure("INVALID_DATAFLOW_REFERENCE", "A task cannot consume its own output");
        var sources = tasks.findDirectInputTasks(task.getId(), task.getJob().getId(), reference);
        if (sources.size() != 1)
            throw failure("INVALID_DATAFLOW_REFERENCE", "Input must identify one same-job direct dependency");
        var source = sources.getFirst();
        if (source.getStatus() != TaskStatus.COMPLETE)
            throw failure("UPSTREAM_OUTPUT_UNAVAILABLE", "Input dependency has not completed successfully");
        // Fetch two to detect ambiguity rather than arbitrarily choosing a successful attempt.
        var successes = executions.findByTaskIdAndStatusOrderByAttemptNumberAsc(
                source.getId(), TaskStatus.COMPLETE, PageRequest.of(0, 2));
        if (successes.size() != 1)
            throw failure("UPSTREAM_OUTPUT_UNAVAILABLE", "Input dependency must have exactly one successful attempt");
        var execution = successes.getFirst();
        if (execution.getOutputText() == null)
            throw failure("TASK_OUTPUT_MISSING", "Input dependency has no captured output");
        try {
            TaskOutputLimits.validate(execution.getOutputText(), execution.getOutputContentType(), properties.output().maxBytes());
        } catch (IllegalArgumentException invalid) {
            throw failure("INCOMPATIBLE_TASK_OUTPUT", "Input dependency output exceeds limits or has an unsupported content type");
        }
        if (!"application/json".equals(execution.getOutputContentType()))
            throw failure("INCOMPATIBLE_TASK_OUTPUT", "This input requires application/json output");
        return new ResolvedTaskInput(reference, execution.getOutputText(), execution.getOutputContentType());
    }

    private static String reference(TaskConfig config) {
        if (config instanceof TransformTaskConfig transform) return transform.inputTaskId();
        if (config instanceof HttpRequestTaskConfig http) return http.bodyTaskId();
        return null;
    }

    private static TaskExecutionException failure(String code, String message) {
        return new TaskExecutionException(code, message);
    }
}
