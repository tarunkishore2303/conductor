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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DAGSchedulerTest {
    private TaskReactiveRepository tasks;
    private JobReactiveRepository jobs;
    private ReactiveOutboxWriter outbox;
    private DAGScheduler scheduler;
    private final UUID jobId = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        tasks = mock(TaskReactiveRepository.class);
        jobs = mock(JobReactiveRepository.class);
        outbox = mock(ReactiveOutboxWriter.class);
        var transactions = mock(TransactionalOperator.class);
        when(transactions.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        scheduler = new DAGScheduler(tasks, jobs, outbox, transactions);
        when(outbox.taskQueue(any())).thenReturn(Mono.empty());
        when(jobs.updateStatus(any(), anyString(), anyLong())).thenReturn(Mono.just(1));
    }

    @Test void jobCreationOnlyQueuesPendingRootsWithTheirGeneration() {
        job(JobStatus.CREATED, FailurePolicy.FAIL_FAST);
        when(tasks.findByJobId(jobId)).thenReturn(Flux.just(task(first, TaskStatus.PENDING, 2), task(second, TaskStatus.PENDING, 0)));
        when(tasks.findDependencyIds(first)).thenReturn(Flux.empty());
        when(tasks.findDependencyIds(second)).thenReturn(Flux.just(first));
        StepVerifier.create(scheduler.scheduleJob(jobId)).verifyComplete();
        var capture = ArgumentCaptor.forClass(TaskEvent.class);
        verify(outbox).taskQueue(capture.capture());
        assertThat(capture.getValue().taskId()).isEqualTo(first);
        assertThat(capture.getValue().generation()).isEqualTo(2);
        verify(jobs).updateStatus(jobId, "RUNNING", 0L);
    }

    @Test void duplicateJobCreatedDoesNotResetRunningJob() {
        job(JobStatus.RUNNING, FailurePolicy.FAIL_FAST);
        StepVerifier.create(scheduler.scheduleJob(jobId)).verifyComplete();
        verifyNoInteractions(outbox, tasks);
        verify(jobs, never()).updateStatus(any(), any(), any());
    }

    @Test void terminalJobCreatedDoesNotResurrectJob() {
        job(JobStatus.CANCELLED, FailurePolicy.FAIL_FAST);
        StepVerifier.create(scheduler.scheduleJob(jobId)).verifyComplete();
        verifyNoInteractions(outbox, tasks);
    }

    @Test void completedTaskQueuesOnlyReadyPendingDownstream() {
        job(JobStatus.RUNNING, FailurePolicy.FAIL_FAST);
        var event = result(TaskStatus.COMPLETE);
        current(event, true);
        when(tasks.findDownstreamTaskIds(first)).thenReturn(Flux.just(second));
        when(tasks.findById(second)).thenReturn(Mono.just(task(second, TaskStatus.PENDING, 1)));
        when(tasks.findDependencyIds(second)).thenReturn(Flux.just(first));
        when(tasks.findById(first)).thenReturn(Mono.just(task(first, TaskStatus.COMPLETE, 0)));
        when(tasks.findByJobId(jobId)).thenReturn(Flux.just(task(first, TaskStatus.COMPLETE, 0), task(second, TaskStatus.PENDING, 1)));
        StepVerifier.create(scheduler.onTaskResult(event)).verifyComplete();
        verify(outbox).taskQueue(argThat(e -> e.taskId().equals(second) && e.generation() == 1));
        verify(tasks, never()).updateStatus(eq(first), any(), any());
    }

    @Test void staleResultDoesNotOverwriteTaskOrDispatch() {
        job(JobStatus.RUNNING, FailurePolicy.FAIL_FAST);
        var event = result(TaskStatus.COMPLETE); current(event, false);
        StepVerifier.create(scheduler.onTaskResult(event)).verifyComplete();
        verifyNoInteractions(outbox);
        verify(tasks, never()).updateStatus(any(), any(), any());
        verify(tasks, never()).findDownstreamTaskIds(any());
    }

    @Test void failFastCancelsPendingAndMarksFailed() {
        job(JobStatus.RUNNING, FailurePolicy.FAIL_FAST);
        var event = result(TaskStatus.DEAD_LETTERED); current(event, true);
        when(tasks.cancelPendingByJobId(jobId)).thenReturn(Mono.just(1));
        StepVerifier.create(scheduler.onTaskResult(event)).verifyComplete();
        verify(tasks).cancelPendingByJobId(jobId);
        verify(tasks, never()).findByJobId(any());
        verify(jobs).updateStatus(jobId, "FAILED", 0L);
        verifyNoInteractions(outbox);
    }

    @Test void cancelledJobIgnoresDelayedResult() {
        job(JobStatus.CANCELLING, FailurePolicy.FAIL_FAST);
        StepVerifier.create(scheduler.onTaskResult(result(TaskStatus.COMPLETE))).verifyComplete();
        verifyNoInteractions(tasks, outbox);
    }

    @Test void continueFailureStillProcessesIndependentCompletions() {
        job(JobStatus.FAILED, FailurePolicy.CONTINUE_ON_FAILURE);
        var event = result(TaskStatus.COMPLETE); current(event, true);
        when(tasks.findDownstreamTaskIds(first)).thenReturn(Flux.empty());
        when(tasks.findByJobId(jobId)).thenReturn(Flux.just(task(first, TaskStatus.COMPLETE, 0), task(second, TaskStatus.DEAD_LETTERED, 0)));
        StepVerifier.create(scheduler.onTaskResult(event)).verifyComplete();
        verify(tasks).findDownstreamTaskIds(first);
        verify(jobs, never()).updateStatus(any(), any(), any());
    }

    @Test void completeRunChangesStateAfterLastTask() {
        job(JobStatus.RUNNING, FailurePolicy.FAIL_FAST);
        var event = result(TaskStatus.COMPLETE); current(event, true);
        when(tasks.findDownstreamTaskIds(first)).thenReturn(Flux.empty());
        when(tasks.findByJobId(jobId)).thenReturn(Flux.just(task(first, TaskStatus.COMPLETE, 0)));
        StepVerifier.create(scheduler.onTaskResult(event)).verifyComplete();
        verify(jobs).updateStatus(jobId, "COMPLETE", 0L);
    }

    @Test void outboxErrorPropagatesToTransactionAndKafkaListener() {
        job(JobStatus.CREATED, FailurePolicy.FAIL_FAST);
        when(tasks.findByJobId(jobId)).thenReturn(Flux.just(task(first, TaskStatus.PENDING, 0)));
        when(tasks.findDependencyIds(first)).thenReturn(Flux.empty());
        when(outbox.taskQueue(any())).thenReturn(Mono.error(new IllegalStateException("storage unavailable")));
        StepVerifier.create(scheduler.scheduleJob(jobId)).expectError(IllegalStateException.class).verify();
    }

    private void current(TaskResultEvent event, boolean matches) {
        when(tasks.matchesCurrentExecution(jobId, first, event.executionId(), event.status().name(),
            event.status() == TaskStatus.COMPLETE ? "COMPLETE" : "FAILED")).thenReturn(Mono.just(matches));
    }
    private void job(JobStatus status, FailurePolicy policy) {
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Mono.just(new JobRecord(jobId, "job", status, policy, 0L)));
    }
    private TaskRecord task(UUID id, TaskStatus status, int generation) {
        return new TaskRecord(id, jobId, "task", status, 2, 0, 0L, generation);
    }
    private TaskResultEvent result(TaskStatus status) {
        return new TaskResultEvent(jobId, first, UUID.randomUUID(), status, null);
    }
}
