package com.loom.worker.repository;

import com.loom.common.model.TaskExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import com.loom.common.model.TaskStatus;
import org.springframework.data.domain.Pageable;
import java.util.UUID;

public interface TaskExecutionJpaRepository extends JpaRepository<TaskExecution, UUID> {

    List<TaskExecution> findByTaskIdAndStatusOrderByAttemptNumberAsc(UUID taskId, TaskStatus status, Pageable limit);

    boolean existsByExecutionId(UUID executionId);

    Optional<TaskExecution> findByExecutionId(UUID executionId);
}
