package com.loom.scheduler.service;

import com.loom.common.event.TaskEvent;
import com.loom.common.event.TaskResultEvent;
import com.loom.common.model.FailurePolicy;
import com.loom.common.model.JobStatus;
import com.loom.common.model.TaskStatus;
import com.loom.scheduler.model.JobRecord;
import com.loom.scheduler.model.TaskRecord;
import com.loom.scheduler.repository.JobReactiveRepository;
import com.loom.scheduler.repository.TaskReactiveRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Function;

/** State transitions and exact dispatch intents share one PostgreSQL transaction. */
@Service
@RequiredArgsConstructor
public class DAGScheduler {
    private final TaskReactiveRepository taskRepo;
    private final JobReactiveRepository jobRepo;
    private final ReactiveOutboxWriter outbox;
    private final TransactionalOperator transactions;

    public Mono<Void> scheduleJob(UUID jobId) {
        return inJobTransaction(jobId, job -> {
            if (job.status() != JobStatus.CREATED) return Mono.empty();
            return updateJobStatus(job, JobStatus.RUNNING)
                .thenMany(taskRepo.findByJobId(jobId))
                .filter(task -> task.status() == TaskStatus.PENDING)
                .filterWhen(this::isRootTask)
                .concatMap(this::dispatch)
                .then();
        });
    }

    /** Worker state is authoritative. Old generation results must never rewrite it. */
    public Mono<Void> onTaskResult(TaskResultEvent event) {
        return inJobTransaction(event.jobId(), job -> {
            if (!acceptsResults(job)) return Mono.empty();
            if (event.status() != TaskStatus.COMPLETE && event.status() != TaskStatus.DEAD_LETTERED)
                return Mono.empty();
            String executionStatus = event.status() == TaskStatus.COMPLETE ? "COMPLETE" : "FAILED";
            return taskRepo.matchesCurrentExecution(event.jobId(), event.taskId(), event.executionId(),
                    event.status().name(), executionStatus)
                .flatMap(matches -> {
                    if (!matches) return Mono.empty();
                    return event.status() == TaskStatus.COMPLETE
                        ? onTaskComplete(job, event.taskId()) : onTaskDeadLettered(job);
                });
        });
    }

    private Mono<Void> onTaskComplete(JobRecord job, UUID completedTaskId) {
        return taskRepo.findDownstreamTaskIds(completedTaskId)
            .concatMap(taskRepo::findById)
            .filter(task -> job.id().equals(task.jobId()) && task.status() == TaskStatus.PENDING)
            .filterWhen(this::allDependenciesComplete)
            .concatMap(this::dispatch)
            .then(checkJobCompletion(job));
    }

    private Mono<Void> onTaskDeadLettered(JobRecord job) {
        if (job.failurePolicy() == FailurePolicy.FAIL_FAST) {
            return taskRepo.findByJobId(job.id())
                .filter(task -> task.status() == TaskStatus.PENDING)
                .concatMap(task -> taskRepo.updateStatus(task.id(), TaskStatus.CANCELLED.name(), task.version()))
                .then(updateJobStatus(job, JobStatus.FAILED));
        }
        return checkJobCompletion(job);
    }

    private Mono<Void> inJobTransaction(UUID jobId, Function<JobRecord, Mono<Void>> operation) {
        // Lock before reading task/dependency state. Every scheduler evaluation of this job
        // sees previously committed evaluations, including parallel-branch completion.
        return Mono.defer(() -> jobRepo.findByIdForUpdate(jobId).flatMap(operation))
            .as(transactions::transactional);
    }

    private boolean acceptsResults(JobRecord job) {
        return job.status() == JobStatus.RUNNING
            || (job.status() == JobStatus.FAILED && job.failurePolicy() == FailurePolicy.CONTINUE_ON_FAILURE);
    }

    private Mono<Boolean> isRootTask(TaskRecord task) {
        return taskRepo.findDependencyIds(task.id()).count().map(count -> count == 0);
    }

    private Mono<Boolean> allDependenciesComplete(TaskRecord task) {
        return taskRepo.findDependencyIds(task.id())
            .concatMap(taskRepo::findById)
            .all(dependency -> dependency.status() == TaskStatus.COMPLETE);
    }

    private Mono<Void> dispatch(TaskRecord task) {
        return outbox.taskQueue(new TaskEvent(task.jobId(), task.id(), task.name(),
            task.maxRetries(), task.retryCount(), task.executionGeneration()));
    }

    private Mono<Void> updateJobStatus(JobRecord job, JobStatus status) {
        if (job.status() == status) return Mono.empty();
        return jobRepo.updateStatus(job.id(), status.name(), job.version())
            .flatMap(updated -> updated == 1 ? Mono.<Void>empty()
                : Mono.error(new IllegalStateException("Concurrent job state transition")));
    }

    private Mono<Void> checkJobCompletion(JobRecord job) {
        return taskRepo.findByJobId(job.id()).collectList().flatMap(tasks -> {
            boolean allDone = tasks.stream().allMatch(task -> isTerminal(task.status()));
            boolean anyFailed = tasks.stream().anyMatch(task -> task.status() == TaskStatus.DEAD_LETTERED
                || task.status() == TaskStatus.FAILED);
            // Preserve CONTINUE_ON_FAILURE: failed state does not suppress independent
            // dispatches; only task/dependency state controls their eligibility.
            JobStatus next = anyFailed ? JobStatus.FAILED : allDone ? JobStatus.COMPLETE : null;
            return next == null ? Mono.empty() : updateJobStatus(job, next);
        });
    }

    private boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.COMPLETE || status == TaskStatus.FAILED
            || status == TaskStatus.DEAD_LETTERED || status == TaskStatus.CANCELLED;
    }
}
