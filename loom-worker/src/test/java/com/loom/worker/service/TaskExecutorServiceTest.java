package com.loom.worker.service;

import com.loom.common.event.TaskEvent;
import com.loom.common.event.TaskResultEvent;
import com.loom.common.model.*;
import com.loom.capability.*;
import com.loom.worker.exception.TaskExecutionException;
import com.loom.worker.repository.TaskExecutionJpaRepository;
import com.loom.worker.repository.WorkerTaskRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import com.loom.common.outbox.JdbcOutboxWriter;
import com.loom.common.event.ExecutionIdentity;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class TaskExecutorServiceTest {

    private WorkerTaskRepository taskRepo;
    private TaskExecutionJpaRepository executionRepo;
    private TaskHandler taskHandler;
    private TaskConfigCodec codec;
    private TaskOutputResolver outputResolver;
    private RedissonClient redisson;
    private JdbcOutboxWriter outbox;
    private TransactionTemplate txTemplate;
    private TaskExecutorService service;

    private final UUID jobId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        taskRepo    = mock(WorkerTaskRepository.class);
        executionRepo = mock(TaskExecutionJpaRepository.class);
        taskHandler = mock(TaskHandler.class);
        when(taskHandler.type()).thenReturn(TaskType.NOOP);
        try { when(taskHandler.execute(any())).thenReturn(TaskExecutionResult.completed()); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
        outputResolver = mock(TaskOutputResolver.class);
        codec = mock(TaskConfigCodec.class);
        when(codec.decode(any(), anyString())).thenReturn(new NoOpTaskConfig());
        redisson    = mock(RedissonClient.class);
        outbox      = mock(JdbcOutboxWriter.class);
        txTemplate  = mock(TransactionTemplate.class);

        RLock lock = mock(RLock.class);
        try { when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true); }
        catch (InterruptedException ignored) {}
        when(redisson.getLock(anyString())).thenReturn(lock);

        // Make TransactionTemplate execute the lambda synchronously
        doAnswer(inv -> {
            ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>)
                inv.getArgument(0)).accept(mock(org.springframework.transaction.TransactionStatus.class));
            return null;
        }).when(txTemplate).executeWithoutResult(any());

        service = new TaskExecutorService(
            taskRepo, executionRepo, new TaskHandlerRegistry(java.util.List.of(taskHandler)), codec, outputResolver, TaskProperties.defaults(), redisson, outbox,
            new SimpleMeterRegistry(), txTemplate
        );
    }

    private Task pendingTask() {
        Task task = new Task();
        task.setId(taskId);
        Job job = new Job(); job.setId(jobId); job.setStatus(JobStatus.RUNNING); task.setJob(job);
        task.setStatus(TaskStatus.PENDING);
        task.setMaxRetries(3);
        task.setRetryCount(0);
        return task;
    }

    @Test
    void unavailableLockPreventsHandlerExecutionAndLeavesMessageRetryable() throws Exception {
        when(taskRepo.findJobStatusByTaskId(taskId)).thenReturn(Optional.of("RUNNING"));
        when(redisson.getLock(anyString()).tryLock(anyLong(), anyLong(), any())).thenReturn(false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.process(new TaskEvent(jobId, taskId, "ignored", 2, 0)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Task lock is currently unavailable");
        verifyNoInteractions(executionRepo);
        verify(taskHandler, never()).execute(any());
    }

    @Test
    void interruptedLockAcquisitionRestoresInterruptAndLeavesMessageRetryable() throws Exception {
        when(redisson.getLock(anyString()).tryLock(anyLong(), anyLong(), any())).thenThrow(new InterruptedException());
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.process(new TaskEvent(jobId, taskId, "interrupted", 2, 0)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Task lock acquisition was interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(executionRepo, outbox);
            verify(taskHandler, never()).execute(any());
        } finally { Thread.interrupted(); }
    }

    @Test
    void controlledHandlerFailurePersistsSafeCodeAndSchedulesRetry() throws Exception {
        when(taskRepo.findLockedById(taskId)).thenReturn(Optional.of(pendingTask()));
        doThrow(new TaskExecutionException("HTTP_STATUS_500", "HTTP endpoint returned status 500"))
                .when(taskHandler).execute(any());
        service.process(new TaskEvent(jobId, taskId, "http", 2, 0));
        var execution = ArgumentCaptor.forClass(TaskExecution.class);
        verify(executionRepo, atLeastOnce()).save(execution.capture());
        assertThat(execution.getValue().getErrorType()).isEqualTo("HTTP_STATUS_500");
        assertThat(execution.getValue().getErrorMessage()).isEqualTo("HTTP endpoint returned status 500");
        var queue = ArgumentCaptor.forClass(TaskEvent.class);
        verify(outbox).taskQueue(queue.capture(), eq(execution.getValue().getRetryScheduledAt()));
        assertThat(queue.getValue().attemptNumber()).isEqualTo(1);
        assertThat(queue.getValue().generation()).isZero();
    }

    @Test
    void invalidStoredConfigurationIsControlledAndDoesNotReachHandler() throws Exception {
        when(taskRepo.findLockedById(taskId)).thenReturn(Optional.of(pendingTask()));
        when(codec.decode(any(), anyString())).thenThrow(new IllegalArgumentException("untrusted parser diagnostics"));
        service.process(new TaskEvent(jobId, taskId, "bad-config", 0, 0));
        verify(taskHandler, never()).execute(any());
        var execution = ArgumentCaptor.forClass(TaskExecution.class);
        verify(executionRepo, atLeastOnce()).save(execution.capture());
        assertThat(execution.getValue().getErrorType()).isEqualTo("INVALID_TASK_CONFIG");
        assertThat(execution.getValue().getErrorMessage()).isEqualTo("Task configuration is invalid");
    }

    @Test
    void successfulExecution_setsCompleteAndPublishes() throws Exception {
        when(taskRepo.findJobStatusByTaskId(taskId)).thenReturn(Optional.of(JobStatus.RUNNING.name()));
        when(executionRepo.existsByExecutionId(any())).thenReturn(false);
        when(taskRepo.findLockedById(taskId)).thenReturn(Optional.of(pendingTask()));
        when(taskRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(executionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.process(new TaskEvent(jobId, taskId, "task-a", 3, 0));

        verify(taskHandler).execute(any());
        ArgumentCaptor<TaskResultEvent> captor = ArgumentCaptor.forClass(TaskResultEvent.class);
        verify(outbox).taskResult(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.COMPLETE);
        ArgumentCaptor<TaskExecution> execution = ArgumentCaptor.forClass(TaskExecution.class);
        verify(executionRepo, atLeastOnce()).save(execution.capture());
        assertThat(execution.getValue().getAttemptNumber()).isZero();
        assertThat(execution.getValue().getCompletedAt()).isNotNull();
        assertThat(execution.getValue().getErrorMessage()).isNull();
    }

    @Test
    void failedExecution_schedulesRetryWhenBelowMaxRetries() throws Exception {
        when(taskRepo.findJobStatusByTaskId(taskId)).thenReturn(Optional.of(JobStatus.RUNNING.name()));
        when(executionRepo.existsByExecutionId(any())).thenReturn(false);
        when(taskRepo.findLockedById(taskId)).thenReturn(Optional.of(pendingTask()));
        when(taskRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(executionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("simulated failure")).when(taskHandler).execute(any());

        service.process(new TaskEvent(jobId, taskId, "task-a", 3, 0));

        // Still has retries — no dead-letter result published yet
        verify(outbox, never()).taskResult(any());
        ArgumentCaptor<TaskExecution> execution = ArgumentCaptor.forClass(TaskExecution.class);
        verify(executionRepo, atLeastOnce()).save(execution.capture());
        assertThat(execution.getValue().getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(execution.getValue().getErrorType()).isEqualTo(RuntimeException.class.getName());
        assertThat(execution.getValue().getErrorMessage()).isEqualTo("simulated failure");
        assertThat(execution.getValue().getRetryScheduledAt()).isAfter(execution.getValue().getCompletedAt());
    }

    @Test
    void failedExecution_deadLettersWhenMaxRetriesExhausted() throws Exception {
        when(taskRepo.findJobStatusByTaskId(taskId)).thenReturn(Optional.of(JobStatus.RUNNING.name()));
        when(executionRepo.existsByExecutionId(any())).thenReturn(false);
        when(taskRepo.findLockedById(taskId)).thenReturn(Optional.of(pendingTask()));
        when(taskRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(executionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("password=secret simulated failure")).when(taskHandler).execute(any());

        service.process(new TaskEvent(jobId, taskId, "task-a", 3, 3));

        ArgumentCaptor<TaskResultEvent> captor = ArgumentCaptor.forClass(TaskResultEvent.class);
        verify(outbox).taskResult(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.DEAD_LETTERED);
        assertThat(captor.getValue().errorMessage()).doesNotContain("secret");
        ArgumentCaptor<Task> task = ArgumentCaptor.forClass(Task.class);
        verify(taskRepo, atLeastOnce()).save(task.capture());
        assertThat(task.getValue().getRetryCount()).isEqualTo(3);
        assertThat(task.getValue().getDeadLetteredAt()).isNotNull();
        ArgumentCaptor<TaskExecution> execution = ArgumentCaptor.forClass(TaskExecution.class);
        verify(executionRepo, atLeastOnce()).save(execution.capture());
        assertThat(execution.getValue().getAttemptNumber()).isEqualTo(3);
        assertThat(execution.getValue().getErrorMessage()).contains("[REDACTED]").doesNotContain("secret");
        assertThat(execution.getValue().getRetryScheduledAt()).isNull();
    }

    @Test
    void cancellingJob_skipsExecution() throws Exception {
        when(taskRepo.findJobStatusByTaskId(taskId)).thenReturn(Optional.of(JobStatus.CANCELLING.name()));

        service.process(new TaskEvent(jobId, taskId, "task-a", 3, 0));

        verify(taskHandler, never()).execute(any());
        verify(txTemplate, never()).executeWithoutResult(any());
    }

    @Test
    void failedFailFastJobSkipsAlreadyPublishedPendingRetry() throws Exception {
        var task = pendingTask();
        task.getJob().setStatus(JobStatus.FAILED);
        task.getJob().setFailurePolicy(FailurePolicy.FAIL_FAST);
        when(taskRepo.findLockedById(taskId)).thenReturn(Optional.of(task));
        service.process(new TaskEvent(jobId, taskId, "late-retry", 2, 1));
        verify(taskHandler, never()).execute(any());
        verifyNoInteractions(outbox);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void failedContinuePolicyJobStillExecutesIndependentPendingWork() throws Exception {
        var task = pendingTask();
        task.getJob().setStatus(JobStatus.FAILED);
        task.getJob().setFailurePolicy(FailurePolicy.CONTINUE_ON_FAILURE);
        when(taskRepo.findLockedById(taskId)).thenReturn(Optional.of(task));
        service.process(new TaskEvent(jobId, taskId, "independent", 2, 0));
        verify(taskHandler).execute(any());
        verify(outbox).taskResult(any());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETE);
    }

    @Test
    void duplicateExecutionId_skipsExecution() throws Exception {
        when(taskRepo.findJobStatusByTaskId(taskId)).thenReturn(Optional.of(JobStatus.RUNNING.name()));
        when(executionRepo.existsByExecutionId(any())).thenReturn(true);

        service.process(new TaskEvent(jobId, taskId, "task-a", 3, 0));

        verify(taskHandler, never()).execute(any());
    }

    @Test
    void successfulOutputIsPersistedBeforeTransactionalResultIntent() throws Exception {
        when(taskRepo.findLockedById(taskId)).thenReturn(Optional.of(pendingTask()));
        when(taskHandler.execute(any())).thenReturn(new TaskExecutionResult("{\"id\":42}", "application/json"));
        doAnswer(inv -> {
            var executions = ArgumentCaptor.forClass(TaskExecution.class);
            verify(executionRepo, atLeastOnce()).save(executions.capture());
            assertThat(executions.getValue().getStatus()).isEqualTo(TaskStatus.COMPLETE);
            assertThat(executions.getValue().getOutputText()).isEqualTo("{\"id\":42}");
            return null;
        }).when(outbox).taskResult(any());
        service.process(new TaskEvent(jobId, taskId, "capture", 0, 0));
        verify(outbox).taskResult(any());
    }

    @Test
    void outboxInsertFailureEscapesRatherThanSchedulingSuccessfulHandlerAgain() {
        when(taskRepo.findLockedById(taskId)).thenReturn(Optional.of(pendingTask()));
        doThrow(new IllegalStateException("database unavailable")).when(outbox).taskResult(any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.process(new TaskEvent(jobId, taskId, "ok", 0, 0)))
                .isInstanceOf(IllegalStateException.class);
        verify(outbox, never()).taskQueue(any(), any());
    }

    @Test
    void obsoleteGenerationDoesNotExecuteOrPublish() throws Exception {
        var task = pendingTask(); task.setExecutionGeneration(1);
        when(taskRepo.findLockedById(taskId)).thenReturn(Optional.of(task));
        service.process(new TaskEvent(jobId, taskId, "stale", 2, 0, 0));
        verify(taskHandler, never()).execute(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void replayGenerationGetsFreshDeterministicExecutionIdentity() throws Exception {
        var task = pendingTask(); task.setExecutionGeneration(1);
        when(taskRepo.findLockedById(taskId)).thenReturn(Optional.of(task));
        service.process(new TaskEvent(jobId, taskId, "replay", 2, 0, 1));
        var executions = ArgumentCaptor.forClass(TaskExecution.class);
        verify(executionRepo, atLeastOnce()).save(executions.capture());
        assertThat(executions.getValue().getExecutionGeneration()).isEqualTo(1);
        assertThat(executions.getValue().getExecutionId()).isEqualTo(ExecutionIdentity.id(taskId, 1, 0))
                .isNotEqualTo(ExecutionIdentity.id(taskId, 0, 0));
    }

    @Test
    void cancellationCommittedAfterInitialCheckStillSkipsDelayedEvent() throws Exception {
        when(taskRepo.findJobStatusByTaskId(taskId)).thenReturn(Optional.of("RUNNING"));
        var task = pendingTask(); task.getJob().setStatus(JobStatus.CANCELLED);
        when(taskRepo.findLockedById(taskId)).thenReturn(Optional.of(task));
        service.process(new TaskEvent(jobId, taskId, "delayed", 2, 1));
        verify(taskHandler, never()).execute(any());
        verifyNoInteractions(outbox);
    }

    @Test void configuredPersistenceBoundaryRejectsOversizeWithoutOutputOrPayloadLeak() throws Exception {
        var defaults = TaskProperties.defaults();
        service = new TaskExecutorService(taskRepo, executionRepo, new TaskHandlerRegistry(java.util.List.of(taskHandler)),
                codec, outputResolver, new TaskProperties(defaults.delay(), defaults.http(), defaults.transform(), new TaskProperties.Output(4)),
                redisson, outbox, new SimpleMeterRegistry(), txTemplate);
        when(taskRepo.findLockedById(taskId)).thenReturn(Optional.of(pendingTask()));
        when(taskHandler.execute(any())).thenReturn(new TaskExecutionResult("sensitive-output", "text/plain"));
        service.process(new TaskEvent(jobId, taskId, "capture", 0, 0));
        var executions = ArgumentCaptor.forClass(TaskExecution.class);
        verify(executionRepo, atLeastOnce()).save(executions.capture());
        assertThat(executions.getValue().getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(executions.getValue().getErrorType()).isEqualTo("TASK_OUTPUT_TOO_LARGE");
        assertThat(executions.getValue().getErrorMessage()).doesNotContain("sensitive-output");
        assertThat(executions.getValue().getOutputText()).isNull();
        assertThat(executions.getValue().getOutputContentType()).isNull();
    }

    @Test void failedTransformAttemptCannotExposeTheNextSuccessfulAttemptsOutput() throws Exception {
        when(taskHandler.type()).thenReturn(TaskType.TRANSFORM);
        service = new TaskExecutorService(taskRepo, executionRepo, new TaskHandlerRegistry(java.util.List.of(taskHandler)),
                codec, outputResolver, TaskProperties.defaults(), redisson, outbox, new SimpleMeterRegistry(), txTemplate);
        var task = pendingTask();
        task.setTaskType(TaskType.TRANSFORM);
        when(taskRepo.findLockedById(taskId)).thenReturn(Optional.of(task));
        when(taskHandler.execute(any())).thenThrow(new TaskExecutionException("TRANSIENT_TRANSFORM_FAILURE", "Transient transform failure"))
                .thenReturn(new TaskExecutionResult("{\"id\":42}", "application/json"));
        service.process(new TaskEvent(jobId, taskId, "transform", 1, 0));
        service.process(new TaskEvent(jobId, taskId, "transform", 1, 1));
        var executions = ArgumentCaptor.forClass(TaskExecution.class);
        verify(executionRepo, atLeastOnce()).save(executions.capture());
        assertThat(executions.getAllValues().stream().filter(e -> e.getAttemptNumber() == 0))
                .allSatisfy(e -> {
                    assertThat(e.getStatus()).isEqualTo(TaskStatus.FAILED);
                    assertThat(e.getOutputText()).isNull();
                    assertThat(e.getOutputContentType()).isNull();
                });
        assertThat(executions.getValue().getAttemptNumber()).isEqualTo(1);
        assertThat(executions.getValue().getStatus()).isEqualTo(TaskStatus.COMPLETE);
        assertThat(executions.getValue().getOutputText()).isEqualTo("{\"id\":42}");
    }
}
