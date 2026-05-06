package com.tarunkishore.loom_api.service;

import com.loom.common.model.Task;
import com.loom.common.model.TaskStatus;
import com.tarunkishore.loom_api.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DlqService {

    private final TaskRepository taskRepository;
    private final TaskQueuePublisher taskQueuePublisher;

    @Transactional(readOnly = true)
    public Page<Task> getDeadLettered(int page, int size) {
        return taskRepository.findByStatus(TaskStatus.DEAD_LETTERED, PageRequest.of(page, size));
    }

    @Transactional
    public Task retryTask(UUID taskId) {
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new NoSuchElementException("Task not found: " + taskId));

        if (task.getStatus() != TaskStatus.DEAD_LETTERED) {
            throw new IllegalStateException("Task is not dead-lettered, current status: " + task.getStatus());
        }

        task.setStatus(TaskStatus.PENDING);
        task.setRetryCount(0);
        Task saved = taskRepository.save(task);

        taskQueuePublisher.publish(saved);
        return saved;
    }
}
