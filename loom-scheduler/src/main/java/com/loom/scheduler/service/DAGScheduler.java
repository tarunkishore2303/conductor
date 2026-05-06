package com.loom.scheduler.service;

import com.loom.common.event.TaskEvent;
import com.loom.common.model.JobStatus;
import com.loom.common.model.TaskStatus;
import com.loom.scheduler.model.JobRecord;
import com.loom.scheduler.model.TaskRecord;
import com.loom.scheduler.repository.JobReactiveRepository;
import com.loom.scheduler.repository.TaskReactiveRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DAGScheduler {

    static final String TASK_QUEUE_TOPIC = "task-queue";

    private final TaskReactiveRepository taskRepo;
    private final JobReactiveRepository jobRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Entry point: called when a new job is created.
     * Loads all tasks, finds root tasks (no dependencies), publishes them to task-queue.
     */
    public Mono<Void> scheduleJob(UUID jobId) {
        log.info("Scheduling job {}", jobId);
        return jobRepo.findById(jobId)
            .flatMap(job -> updateJobStatus(job, JobStatus.RUNNING))
            .thenMany(taskRepo.findByJobId(jobId))
            .filterWhen(this::isRootTask)
            .flatMap(this::publishTask)
            .then();
    }

    /**
     * Called when a task completes successfully.
     * Finds downstream tasks and publishes those whose all dependencies are now COMPLETE.
     */
    public Mono<Void> onTaskComplete(UUID jobId, UUID completedTaskId) {
        log.info("Task {} completed, checking downstream tasks for job {}", completedTaskId, jobId);
        return taskRepo.findDownstreamTaskIds(completedTaskId)
            .flatMap(taskRepo::findById)
            .filterWhen(this::allDependenciesComplete)
            .flatMap(this::publishTask)
            .then()
            .then(checkJobCompletion(jobId));
    }

    /**
     * Called when a task is dead-lettered (max retries exhausted).
     * Applies failure policy: FAIL_FAST cancels all PENDING tasks.
     */
    public Mono<Void> onTaskDeadLettered(UUID jobId) {
        return jobRepo.findById(jobId)
            .flatMap(job -> {
                if (job.failurePolicy().name().equals("FAIL_FAST")) {
                    return cancelPendingTasks(jobId)
                        .then(updateJobStatus(job, JobStatus.FAILED));
                }
                return checkJobCompletion(jobId);
            })
            .then();
    }

    private Mono<Boolean> isRootTask(TaskRecord task) {
        return taskRepo.findDependencyIds(task.id()).count().map(count -> count == 0);
    }

    private Mono<Boolean> allDependenciesComplete(TaskRecord task) {
        return taskRepo.findDependencyIds(task.id())
            .flatMap(taskRepo::findById)
            .all(dep -> dep.status() == TaskStatus.COMPLETE);
    }

    private Mono<Void> publishTask(TaskRecord task) {
        TaskEvent event = new TaskEvent(
            task.jobId(), task.id(), task.name(), task.maxRetries(), task.retryCount()
        );
        log.info("Publishing task {} ({}) to {}", task.name(), task.id(), TASK_QUEUE_TOPIC);
        return Mono.fromFuture(kafkaTemplate.send(TASK_QUEUE_TOPIC, task.id().toString(), event)
            .toCompletableFuture())
            .then();
    }

    private Mono<JobRecord> updateJobStatus(JobRecord job, JobStatus newStatus) {
        return jobRepo.updateStatus(job.id(), newStatus.name(), job.version())
            .flatMap(updated -> {
                if (updated == 0) {
                    return Mono.error(new IllegalStateException(
                        "Optimistic lock conflict updating job " + job.id()));
                }
                return jobRepo.findById(job.id());
            })
            .map(opt -> opt);
    }

    private Mono<Void> cancelPendingTasks(UUID jobId) {
        return taskRepo.findByJobId(jobId)
            .filter(t -> t.status() == TaskStatus.PENDING)
            .flatMap(t -> taskRepo.updateStatus(t.id(), TaskStatus.CANCELLED.name(), t.version()))
            .then();
    }

    private Mono<Void> checkJobCompletion(UUID jobId) {
        return taskRepo.findByJobId(jobId).collectList()
            .flatMap(tasks -> {
                boolean allDone = tasks.stream()
                    .allMatch(t -> isTerminal(t.status()));
                boolean anyFailed = tasks.stream()
                    .anyMatch(t -> t.status() == TaskStatus.DEAD_LETTERED || t.status() == TaskStatus.FAILED);

                return jobRepo.findById(jobId).flatMap(job -> {
                    JobStatus next = anyFailed ? JobStatus.FAILED
                        : allDone ? JobStatus.COMPLETE
                        : null;
                    if (next != null) {
                        return updateJobStatus(job, next).then();
                    }
                    return Mono.empty();
                });
            });
    }

    private boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.COMPLETE
            || status == TaskStatus.FAILED
            || status == TaskStatus.DEAD_LETTERED
            || status == TaskStatus.CANCELLED;
    }
}
