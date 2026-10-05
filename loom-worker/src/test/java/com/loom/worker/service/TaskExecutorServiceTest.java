package com.loom.worker.service;

import com.loom.common.event.TaskEvent;
import com.loom.common.event.TaskResultEvent;
import com.loom.common.model.*;
import com.loom.worker.repository.TaskExecutionJpaRepository;
import com.loom.worker.repository.WorkerTaskRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class TaskExecutorServiceTest {

    private WorkerTaskRepository taskRepo;
    private TaskExecutionJpaRepository executionRepo;
    private TaskHandler taskHandler;
    private RedissonClient redisson;
    private KafkaTemplate<String, Object> kafka;
    private TransactionTemplate txTemplate;
    private TaskExecutorService service;

    private final UUID jobId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        taskRepo    = mock(WorkerTaskRepository.class);
        executionRepo = mock(TaskExecutionJpaRepository.class);
        taskHandler = mock(TaskHandler.class);
        redisson    = mock(RedissonClient.class);
        kafka       = mock(KafkaTemplate.class);
        txTemplate  = mock(TransactionTemplate.class);

        RLock lock = mock(RLock.class);
        try { when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true); }
        catch (InterruptedException ignored) {}
        when(redisson.getLock(anyString())).thenReturn(lock);

        when(kafka.send(anyString(), anyString(), any()))
            .thenReturn(CompletableFuture.completedFuture(null));

        // Make TransactionTemplate execute the lambda synchronously
        doAnswer(inv -> {
            ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>)
                inv.getArgument(0)).accept(mock(org.springframework.transaction.TransactionStatus.class));
            return null;
        }).when(txTemplate).executeWithoutResult(any());

        service = new TaskExecutorService(
            taskRepo, executionRepo, taskHandler, redisson, kafka,
            Executors.newSingleThreadScheduledExecutor(),
            new SimpleMeterRegistry(), txTemplate
        );
    }

    private Task pendingTask() {
        Task task = new Task();
        task.setStatus(TaskStatus.PENDING);
        task.setMaxRetries(3);
        task.setRetryCount(0);
        return task;
    }

    @Test
    void successfulExecution_setsCompleteAndPublishes() throws Exception {
        when(taskRepo.findJobStatusByTaskId(taskId)).thenReturn(Optional.of(JobStatus.RUNNING.name()));
        when(executionRepo.existsByExecutionId(any())).thenReturn(false);
        when(taskRepo.findById(taskId)).thenReturn(Optional.of(pendingTask()));
        when(taskRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(executionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.process(new TaskEvent(jobId, taskId, "task-a", 3, 0));

        verify(taskHandler).execute(any());
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(TaskExecutorService.TASK_RESULTS_TOPIC), eq(taskId.toString()), captor.capture());
        assertThat(((TaskResultEvent) captor.getValue()).status()).isEqualTo(TaskStatus.COMPLETE);
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
        when(taskRepo.findById(taskId)).thenReturn(Optional.of(pendingTask()));
        when(taskRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(executionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("simulated failure")).when(taskHandler).execute(any());

        service.process(new TaskEvent(jobId, taskId, "task-a", 3, 0));

        // Still has retries — no dead-letter result published yet
        verify(kafka, never()).send(eq(TaskExecutorService.TASK_RESULTS_TOPIC), any(), any());
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
        when(taskRepo.findById(taskId)).thenReturn(Optional.of(pendingTask()));
        when(taskRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(executionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("password=secret simulated failure")).when(taskHandler).execute(any());

        service.process(new TaskEvent(jobId, taskId, "task-a", 3, 3));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(TaskExecutorService.TASK_RESULTS_TOPIC), eq(taskId.toString()), captor.capture());
        assertThat(((TaskResultEvent) captor.getValue()).status()).isEqualTo(TaskStatus.DEAD_LETTERED);
        assertThat(((TaskResultEvent) captor.getValue()).errorMessage()).doesNotContain("secret");
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
    void duplicateExecutionId_skipsExecution() throws Exception {
        when(taskRepo.findJobStatusByTaskId(taskId)).thenReturn(Optional.of(JobStatus.RUNNING.name()));
        when(executionRepo.existsByExecutionId(any())).thenReturn(true);

        service.process(new TaskEvent(jobId, taskId, "task-a", 3, 0));

        verify(taskHandler, never()).execute(any());
    }

    @Test
    void terminalResultIsPublishedOnlyAfterCommit() {
        when(taskRepo.findJobStatusByTaskId(taskId)).thenReturn(Optional.of(JobStatus.RUNNING.name()));
        when(taskRepo.findById(taskId)).thenReturn(Optional.of(pendingTask()));
        when(taskRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(executionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.process(new TaskEvent(jobId, taskId, "task-a", 0, 0));
            verify(kafka, never()).send(anyString(), anyString(), any());
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            verify(kafka).send(eq(TaskExecutorService.TASK_RESULTS_TOPIC), eq(taskId.toString()), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
