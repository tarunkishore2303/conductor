package com.loom.worker.service;

import com.loom.common.event.TaskEvent;
import com.loom.common.event.TaskResultEvent;
import com.loom.common.model.*;
import com.loom.worker.repository.TaskExecutionJpaRepository;
import com.loom.worker.repository.WorkerTaskRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExecutorService {

    static final String TASK_RESULTS_TOPIC = "task-results";
    static final String TASK_QUEUE_TOPIC   = "task-queue";

    private final WorkerTaskRepository taskRepo;
    private final TaskExecutionJpaRepository executionRepo;
    private final TaskHandler taskHandler;
    private final RedissonClient redisson;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ScheduledExecutorService retryExecutor;
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
            log.warn("Lock acquisition interrupted for task {}", event.taskId());
            return;
        }

        if (!acquired) {
            log.warn("Could not acquire lock for task {} — another worker is handling it", event.taskId());
            return;
        }

        try {
            // TransactionTemplate avoids the Spring AOP self-invocation problem.
            transactionTemplate.executeWithoutResult(status -> executeInTransaction(event));
        } finally {
            lock.unlock();
        }
    }

    private void executeInTransaction(TaskEvent event) {
        UUID executionId = UUID.nameUUIDFromBytes(
            (event.taskId() + "-" + event.attemptNumber()).getBytes(StandardCharsets.UTF_8));

        if (executionRepo.existsByExecutionId(executionId)) {
            log.info("Execution {} already recorded — skipping duplicate", executionId);
            return;
        }

        Task task = taskRepo.findById(event.taskId()).orElseThrow(
            () -> new IllegalArgumentException("Task not found: " + event.taskId()));

        if (task.getStatus() != TaskStatus.PENDING) {
            log.info("Task {} already in status {} — skipping", event.taskId(), task.getStatus());
            return;
        }

        TaskExecution execution = new TaskExecution();
        execution.setTask(task);
        execution.setExecutionId(executionId);
        execution.setStatus(TaskStatus.RUNNING);
        executionRepo.save(execution);

        task.setStatus(TaskStatus.RUNNING);
        taskRepo.save(task);

        Timer.Sample timerSample = Timer.start(meterRegistry);
        try {
            taskHandler.execute(event);

            execution.setStatus(TaskStatus.COMPLETE);
            execution.setCompletedAt(Instant.now());
            executionRepo.save(execution);

            task.setStatus(TaskStatus.COMPLETE);
            taskRepo.save(task);

            timerSample.stop(meterRegistry.timer("loom.task.execution.duration",
                "taskName", event.taskName(), "outcome", "success"));
            meterRegistry.counter("loom.tasks.completed", "taskName", event.taskName()).increment();

            kafkaTemplate.send(TASK_RESULTS_TOPIC, event.taskId().toString(),
                new TaskResultEvent(event.jobId(), event.taskId(), executionId, TaskStatus.COMPLETE, null));

        } catch (Exception ex) {
            log.error("Task {} failed on attempt {}: {}", event.taskId(), event.attemptNumber(), ex.getMessage());

            execution.setStatus(TaskStatus.FAILED);
            execution.setCompletedAt(Instant.now());
            executionRepo.save(execution);

            timerSample.stop(meterRegistry.timer("loom.task.execution.duration",
                "taskName", event.taskName(), "outcome", "failure"));
            meterRegistry.counter("loom.tasks.failed", "taskName", event.taskName()).increment();

            int nextAttempt = event.attemptNumber() + 1;
            if (nextAttempt <= event.maxRetries()) {
                scheduleRetry(event, nextAttempt);
                task.setStatus(TaskStatus.PENDING);
                taskRepo.save(task);
            } else {
                task.setStatus(TaskStatus.DEAD_LETTERED);
                taskRepo.save(task);
                kafkaTemplate.send(TASK_RESULTS_TOPIC, event.taskId().toString(),
                    new TaskResultEvent(event.jobId(), event.taskId(), executionId,
                        TaskStatus.DEAD_LETTERED, ex.getMessage()));
            }
        }
    }

    private void scheduleRetry(TaskEvent event, int nextAttempt) {
        long delaySeconds = (long) Math.pow(2, event.attemptNumber());
        log.info("Scheduling retry {} for task {} in {}s", nextAttempt, event.taskId(), delaySeconds);
        retryExecutor.schedule(() -> {
            MDC.put("jobId", event.jobId().toString());
            MDC.put("taskId", event.taskId().toString());
            try {
                kafkaTemplate.send(TASK_QUEUE_TOPIC, event.taskId().toString(),
                    new TaskEvent(event.jobId(), event.taskId(), event.taskName(),
                        event.maxRetries(), nextAttempt));
            } finally {
                MDC.clear();
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }
}
