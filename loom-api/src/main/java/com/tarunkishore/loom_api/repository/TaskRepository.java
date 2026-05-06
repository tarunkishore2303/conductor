package com.tarunkishore.loom_api.repository;

import com.loom.common.model.Task;
import com.loom.common.model.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    List<Task> findByJobId(UUID jobId);

    List<Task> findByJobIdAndStatus(UUID jobId, TaskStatus status);

    Page<Task> findByStatus(TaskStatus status, Pageable pageable);
}
