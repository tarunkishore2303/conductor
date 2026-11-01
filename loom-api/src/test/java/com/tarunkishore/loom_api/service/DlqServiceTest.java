package com.tarunkishore.loom_api.service;

import com.loom.common.event.TaskEvent;
import com.loom.common.model.Job;
import com.loom.common.model.JobStatus;
import com.loom.common.model.Task;
import com.loom.common.model.TaskStatus;
import com.loom.common.outbox.JdbcOutboxWriter;
import com.tarunkishore.loom_api.repository.JobRepository;
import com.tarunkishore.loom_api.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DlqServiceTest {
    final TaskRepository tasks = mock(TaskRepository.class);
    final JobRepository jobs = mock(JobRepository.class);
    final JdbcOutboxWriter outbox = mock(JdbcOutboxWriter.class);
    final DlqService service = new DlqService(tasks, jobs, outbox);
    final Job job = new Job();
    final Task task = new Task();

    @BeforeEach void setup() {
        job.setId(UUID.randomUUID());
        job.setStatus(JobStatus.FAILED);
        task.setId(UUID.randomUUID());
        task.setJob(job);
        task.setName("HTTP failure");
        task.setMaxRetries(2);
        task.setRetryCount(2);
        task.setStatus(TaskStatus.DEAD_LETTERED);
        task.setDeadLetteredAt(Instant.now());
        when(tasks.findJobId(task.getId())).thenReturn(Optional.of(job.getId()));
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(tasks.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
        when(tasks.save(task)).thenReturn(task);
    }

    @Test void replayStartsNewGenerationAndResumesFailedJobWithDurableIntent() {
        service.retryTask(task.getId());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getExecutionGeneration()).isEqualTo(1);
        assertThat(task.getRetryCount()).isZero();
        assertThat(task.getDeadLetteredAt()).isNull();
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        var event = ArgumentCaptor.forClass(TaskEvent.class);
        verify(outbox).taskQueue(event.capture(), any(Instant.class));
        assertThat(event.getValue()).isEqualTo(new TaskEvent(job.getId(), task.getId(), task.getName(), 2, 0, 1));
        var order = inOrder(tasks, jobs, outbox);
        order.verify(tasks).findJobId(task.getId());
        order.verify(jobs).findByIdForUpdate(job.getId());
        order.verify(tasks).findByIdForUpdate(task.getId());
        order.verify(tasks).save(task);
        order.verify(outbox).taskQueue(any(), any());
    }

    @Test void duplicateReplayConflictsRatherThanCreatingAnotherGeneration() {
        service.retryTask(task.getId());
        assertThatThrownBy(() -> service.retryTask(task.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(task.getExecutionGeneration()).isEqualTo(1);
        verify(outbox, times(1)).taskQueue(any(), any());
    }

    @Test void cancelledJobCannotBeResurrected() {
        job.setStatus(JobStatus.CANCELLED);
        assertThatThrownBy(() -> service.retryTask(task.getId())).isInstanceOf(IllegalStateException.class);
        verify(tasks, never()).findByIdForUpdate(any());
        verifyNoInteractions(outbox);
    }

    @Test void cancellingJobCannotBeReplayed() {
        job.setStatus(JobStatus.CANCELLING);
        assertThatThrownBy(() -> service.retryTask(task.getId())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(outbox);
    }

    @Test void runningJobKeepsItsStatusAndIncrementsExistingGeneration() {
        job.setStatus(JobStatus.RUNNING);
        task.setExecutionGeneration(3);
        service.retryTask(task.getId());
        assertThat(task.getExecutionGeneration()).isEqualTo(4);
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        verify(outbox).taskQueue(eq(new TaskEvent(job.getId(), task.getId(), task.getName(), 2, 0, 4)), any());
    }
}
