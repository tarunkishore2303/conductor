package com.tarunkishore.loom_api.service;

import com.loom.common.model.Task;
import com.loom.common.model.TaskStatus;
import com.loom.common.model.JobStatus;
import com.loom.common.event.TaskEvent;
import com.loom.common.outbox.JdbcOutboxWriter;
import com.tarunkishore.loom_api.repository.JobRepository;
import com.tarunkishore.loom_api.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.UUID;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class DlqService {

    private final TaskRepository taskRepository;
    private final JobRepository jobRepository;
    private final JdbcOutboxWriter outbox;

    @Transactional(readOnly = true)
    public Page<Task> getDeadLettered(int page, int size) {
        return taskRepository.findByStatus(TaskStatus.DEAD_LETTERED, PageRequest.of(page, size));
    }

    @Transactional
    public Task retryTask(UUID taskId) {
        UUID jobId = taskRepository.findJobId(taskId)
            .orElseThrow(() -> new NoSuchElementException("Task not found: " + taskId));
        // API replay locks the job before its task. This serializes replay,
        // cancellation and scheduler evaluation without changing historical attempts.
        var job = jobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new NoSuchElementException("Job not found: " + jobId));
        if (job.getStatus() == JobStatus.CANCELLING || job.getStatus() == JobStatus.CANCELLED
                || job.getStatus() == JobStatus.COMPLETE) {
            throw new IllegalStateException("Job cannot replay tasks in state: " + job.getStatus());
        }
        Task task = taskRepository.findByIdForUpdate(taskId)
            .orElseThrow(() -> new NoSuchElementException("Task not found: " + taskId));

        if (task.getStatus() != TaskStatus.DEAD_LETTERED) {
            throw new IllegalStateException("Task is not dead-lettered, current status: " + task.getStatus());
        }

        task.setStatus(TaskStatus.PENDING);
        task.setRetryCount(0);
        task.setExecutionGeneration(Math.addExact(task.getExecutionGeneration(), 1));
        // This timestamp describes current DLQ state; failed TaskExecution evidence is immutable.
        task.setDeadLetteredAt(null);
        if (job.getStatus() == JobStatus.FAILED) job.setStatus(JobStatus.RUNNING);
        Task saved = taskRepository.save(task);

        outbox.taskQueue(new TaskEvent(jobId, saved.getId(), saved.getName(), saved.getMaxRetries(),
                0, saved.getExecutionGeneration()), Instant.now());
        return saved;
    }
}
