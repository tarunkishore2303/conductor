package com.loom.scheduler.service;

import com.loom.common.model.FailurePolicy;
import com.loom.common.model.JobStatus;
import com.loom.common.model.TaskStatus;
import com.loom.scheduler.model.JobRecord;
import com.loom.scheduler.model.TaskRecord;
import com.loom.scheduler.repository.JobReactiveRepository;
import com.loom.scheduler.repository.TaskReactiveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class DAGSchedulerTest {

    private TaskReactiveRepository taskRepo;
    private JobReactiveRepository jobRepo;
    private KafkaTemplate<String, Object> kafka;
    private DAGScheduler scheduler;

    private final UUID jobId = UUID.randomUUID();
    private final UUID taskA = UUID.randomUUID();
    private final UUID taskB = UUID.randomUUID();
    private final UUID taskC = UUID.randomUUID();

    @BeforeEach
    void setup() {
        taskRepo = mock(TaskReactiveRepository.class);
        jobRepo = mock(JobReactiveRepository.class);
        kafka = mock(KafkaTemplate.class);
        scheduler = new DAGScheduler(taskRepo, jobRepo, kafka);

        when(kafka.send(anyString(), anyString(), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void scheduleJob_publishesOnlyRootTasks() {
        JobRecord job = new JobRecord(jobId, "test-job", JobStatus.CREATED, FailurePolicy.FAIL_FAST, 0L);
        JobRecord runningJob = new JobRecord(jobId, "test-job", JobStatus.RUNNING, FailurePolicy.FAIL_FAST, 1L);
        TaskRecord root = new TaskRecord(taskA, jobId, "task-a", TaskStatus.PENDING, 3, 0, 0L);
        TaskRecord dependent = new TaskRecord(taskB, jobId, "task-b", TaskStatus.PENDING, 3, 0, 0L);

        when(jobRepo.findById(jobId)).thenReturn(Mono.just(job), Mono.just(runningJob));
        when(jobRepo.updateStatus(eq(jobId), eq(JobStatus.RUNNING.name()), eq(0L))).thenReturn(Mono.just(1));
        when(taskRepo.findByJobId(jobId)).thenReturn(Flux.just(root, dependent));
        when(taskRepo.findDependencyIds(taskA)).thenReturn(Flux.empty());        // root
        when(taskRepo.findDependencyIds(taskB)).thenReturn(Flux.just(taskA));    // not root

        StepVerifier.create(scheduler.scheduleJob(jobId)).verifyComplete();

        verify(kafka, times(1)).send(eq(DAGScheduler.TASK_QUEUE_TOPIC), eq(taskA.toString()), any());
        verify(kafka, never()).send(eq(DAGScheduler.TASK_QUEUE_TOPIC), eq(taskB.toString()), any());
    }

    @Test
    void onTaskComplete_unblocksDependentWithAllDepsComplete() {
        TaskRecord taskARecord = new TaskRecord(taskA, jobId, "task-a", TaskStatus.COMPLETE, 3, 0, 1L);
        TaskRecord taskBRecord = new TaskRecord(taskB, jobId, "task-b", TaskStatus.PENDING, 3, 0, 0L);
        TaskRecord taskCRecord = new TaskRecord(taskC, jobId, "task-c", TaskStatus.PENDING, 3, 0, 0L);

        // taskA completed → downstream: taskB and taskC
        when(taskRepo.findDownstreamTaskIds(taskA)).thenReturn(Flux.just(taskB, taskC));
        when(taskRepo.findById(taskB)).thenReturn(Mono.just(taskBRecord));
        when(taskRepo.findById(taskC)).thenReturn(Mono.just(taskCRecord));
        when(taskRepo.findById(taskA)).thenReturn(Mono.just(taskARecord));

        // taskB only depends on taskA (COMPLETE) → unblocked
        when(taskRepo.findDependencyIds(taskB)).thenReturn(Flux.just(taskA));
        // taskC depends on taskA + taskB (taskB still PENDING) → blocked
        when(taskRepo.findDependencyIds(taskC)).thenReturn(Flux.just(taskA, taskB));

        // job completion check: still tasks pending, no terminal update
        when(taskRepo.findByJobId(jobId)).thenReturn(Flux.just(taskARecord, taskBRecord, taskCRecord));
        when(jobRepo.findById(jobId)).thenReturn(Mono.just(
            new JobRecord(jobId, "j", JobStatus.RUNNING, FailurePolicy.FAIL_FAST, 1L)));

        StepVerifier.create(scheduler.onTaskComplete(jobId, taskA)).verifyComplete();

        verify(kafka, times(1)).send(eq(DAGScheduler.TASK_QUEUE_TOPIC), eq(taskB.toString()), any());
        verify(kafka, never()).send(eq(DAGScheduler.TASK_QUEUE_TOPIC), eq(taskC.toString()), any());
    }

    @Test
    void onTaskComplete_marksJobCompleteWhenAllTasksDone() {
        TaskRecord taskARecord = new TaskRecord(taskA, jobId, "task-a", TaskStatus.COMPLETE, 3, 0, 1L);
        TaskRecord taskBRecord = new TaskRecord(taskB, jobId, "task-b", TaskStatus.COMPLETE, 3, 0, 1L);
        JobRecord runningJob = new JobRecord(jobId, "j", JobStatus.RUNNING, FailurePolicy.FAIL_FAST, 1L);

        when(taskRepo.findDownstreamTaskIds(taskB)).thenReturn(Flux.empty());
        when(taskRepo.findByJobId(jobId)).thenReturn(Flux.just(taskARecord, taskBRecord));
        when(jobRepo.findById(jobId)).thenReturn(Mono.just(runningJob));
        when(jobRepo.updateStatus(eq(jobId), eq(JobStatus.COMPLETE.name()), eq(1L)))
            .thenReturn(Mono.just(1));

        StepVerifier.create(scheduler.onTaskComplete(jobId, taskB)).verifyComplete();

        verify(jobRepo).updateStatus(jobId, JobStatus.COMPLETE.name(), 1L);
    }
}
