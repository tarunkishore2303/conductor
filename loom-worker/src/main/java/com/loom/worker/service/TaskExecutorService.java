package com.loom.worker.service;

import com.loom.worker.exception.TaskExecutionException;

import com.loom.common.event.TaskEvent;
import com.loom.common.event.TaskResultEvent;
import com.loom.common.event.ExecutionIdentity;
import com.loom.common.outbox.JdbcOutboxWriter;
import com.loom.common.model.*;
import com.loom.common.security.FailureEvidenceSanitizer;
import com.loom.worker.repository.TaskExecutionJpaRepository;
import com.loom.worker.repository.WorkerTaskRepository;
import io.micrometer.core.instrument.MeterRegistry;
import com.loom.capability.TaskConfigCodec;
import com.loom.capability.TaskConfig;
import com.loom.capability.TaskProperties;
import com.loom.capability.TaskOutputLimits;
import com.loom.observability.OperationMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import jakarta.persistence.PersistenceException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExecutorService {

    private final WorkerTaskRepository taskRepo;
    private final TaskExecutionJpaRepository executionRepo;
    private final TaskHandlerRegistry handlers;
    private final TaskConfigCodec configCodec;
    private final TaskOutputResolver outputResolver;
    private final TaskProperties taskProperties;
    private final RedissonClient redisson;
    private final JdbcOutboxWriter outbox;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;

    public void process(TaskEvent event) {
        MDC.put("jobId", event.jobId().toString());
        MDC.put("taskId", event.taskId().toString());
        try {
            doProcess(event);
        } finally {
            MDC.clear();
        }
    }

    private void doProcess(TaskEvent event) {
        String jobStatus = taskRepo.findJobStatusByTaskId(event.taskId()).orElse("");
        if (JobStatus.CANCELLING.name().equals(jobStatus) || JobStatus.CANCELLED.name().equals(jobStatus)) {
            log.info("Skipping task {} — job is {}", event.taskId(), jobStatus);
            return;
        }

        RLock lock = redisson.getLock("task-lock:" + event.taskId());
        boolean acquired;
        try {
            acquired = lock.tryLock(5, 60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Task lock acquisition was interrupted", e);
        }

        if (!acquired) {
            // Do not acknowledge: the previous holder may have crashed before committing.
            throw new IllegalStateException("Task lock is currently unavailable");
        }

        try {
            // TransactionTemplate avoids the Spring AOP self-invocation problem.
            transactionTemplate.executeWithoutResult(status -> executeInTransaction(event));
        } finally {
            lock.unlock();
        }
    }

    private void executeInTransaction(TaskEvent event) {
        UUID executionId = ExecutionIdentity.id(event.taskId(), event.generation(), event.attemptNumber());

        if (executionRepo.existsByExecutionId(executionId)) {
            log.info("Execution {} already recorded — skipping duplicate", executionId);
            return;
        }

        Task task = taskRepo.findLockedById(event.taskId()).orElseThrow(
            () -> new IllegalArgumentException("Task not found: " + event.taskId()));

        if (task.getJob() == null || !event.jobId().equals(task.getJob().getId())) {
            throw new IllegalArgumentException("Task event scope does not match persisted job");
        }

        // Recheck after the database task lock; the earlier delivery may have committed while we waited.
        if (executionRepo.existsByExecutionId(executionId)) return;

        if (task.getExecutionGeneration() != event.generation()) {
            log.info("Ignoring obsolete generation for task {}", event.taskId());
            return;
        }
        JobStatus jobStatus = task.getJob().getStatus();
        if (jobStatus == JobStatus.CANCELLING || jobStatus == JobStatus.CANCELLED || jobStatus == JobStatus.COMPLETE
                || (jobStatus == JobStatus.FAILED && task.getJob().getFailurePolicy() == FailurePolicy.FAIL_FAST)) {
            return;
        }

        if (task.getStatus() != TaskStatus.PENDING) {
            log.info("Task {} already in status {} — skipping", event.taskId(), task.getStatus());
            return;
        }

        TaskExecution execution = new TaskExecution();
        execution.setTask(task);
        execution.setExecutionId(executionId);
        execution.setStatus(TaskStatus.RUNNING);
        execution.setAttemptNumber(event.attemptNumber());
        execution.setExecutionGeneration(event.generation());
        executionRepo.save(execution);

        task.setStatus(TaskStatus.RUNNING);
        task.setRetryCount(event.attemptNumber());
        taskRepo.save(task);

        var observation = OperationMetrics.startHandler(meterRegistry,
                OperationMetrics.HandlerType.valueOf(task.getTaskType().name()));
        try {
            TaskConfig config;
            try {
                config = configCodec.decode(task.getTaskType(), task.getTaskConfigJson());
            } catch (IllegalArgumentException invalid) {
                throw new TaskExecutionException("INVALID_TASK_CONFIG", "Task configuration is invalid");
            }
            var input = outputResolver.resolve(task, config);
            var result = handlers.requireHandler(task.getTaskType()).execute(new TaskExecutionContext(event, config, input));
            if (result == null) throw new TaskExecutionException("INVALID_TASK_OUTPUT", "Handler did not return a result");
            try { TaskOutputLimits.validate(result.output(), result.contentType(), taskProperties.output().maxBytes()); }
            catch (IllegalArgumentException invalid) {
                throw new TaskExecutionException("TASK_OUTPUT_TOO_LARGE", "Task output exceeds supported bounds or content type");
            }
            // Result publication is deliberately outside this handler catch. A database/outbox
            // failure must roll back the attempt, not be misclassified as a capability failure.
            complete(task, execution, result);
        } catch (DataAccessException | PersistenceException databaseFailure) {
            // Resolver/repository failures are infrastructure failures: retry Kafka delivery after rollback.
            observation.failure();
            throw databaseFailure;
        } catch (Exception ex) {
            observation.failure();
            fail(task, execution, event, ex);
            return;
        } finally {
            observation.close();
        }
        outbox.taskResult(new TaskResultEvent(event.jobId(), event.taskId(), executionId, TaskStatus.COMPLETE, null));
    }

    private void complete(Task task, TaskExecution execution, TaskExecutionResult result) {
        execution.setOutputText(result.output());
        execution.setOutputContentType(result.contentType());
        execution.setStatus(TaskStatus.COMPLETE);
        execution.setCompletedAt(Instant.now());
        task.setStatus(TaskStatus.COMPLETE);
        meterRegistry.counter("loom.tasks.completed", "handler_type", task.getTaskType().name()).increment();
    }

    private void fail(Task task, TaskExecution execution, TaskEvent event, Exception ex) {
        String safeError = FailureEvidenceSanitizer.sanitize(ex.getMessage());
        log.error("Task {} failed on attempt {}: {}", event.taskId(), event.attemptNumber(), safeError);

        execution.setStatus(TaskStatus.FAILED);
        execution.setOutputText(null);
        execution.setOutputContentType(null);
        execution.setCompletedAt(Instant.now());
        execution.setErrorType(ex instanceof TaskExecutionException controlled
                ? controlled.errorType() : ex.getClass().getName());
        execution.setErrorMessage(safeError);
        executionRepo.save(execution);

        meterRegistry.counter("loom.tasks.failed", "handler_type", task.getTaskType().name()).increment();

        int nextAttempt = event.attemptNumber() + 1;
        if (nextAttempt <= event.maxRetries()) {
            long delaySeconds = retryDelaySeconds(event);
            execution.setRetryScheduledAt(Instant.now().plusSeconds(delaySeconds));
            outbox.taskQueue(new TaskEvent(event.jobId(), event.taskId(), event.taskName(),
                    event.maxRetries(), nextAttempt, event.generation()), execution.getRetryScheduledAt());
            task.setStatus(TaskStatus.PENDING);
            taskRepo.save(task);
        } else {
            task.setStatus(TaskStatus.DEAD_LETTERED);
            task.setDeadLetteredAt(Instant.now());
            taskRepo.save(task);
            outbox.taskResult(new TaskResultEvent(event.jobId(), event.taskId(), execution.getExecutionId(),
                    TaskStatus.DEAD_LETTERED, safeError));
        }
    }

    private static long retryDelaySeconds(TaskEvent event) {
        return (long) Math.pow(2, event.attemptNumber());
    }
}
