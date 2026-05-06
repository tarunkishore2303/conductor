package com.loom.worker.repository;

import com.loom.common.model.TaskExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TaskExecutionJpaRepository extends JpaRepository<TaskExecution, UUID> {

    boolean existsByExecutionId(UUID executionId);

    Optional<TaskExecution> findByExecutionId(UUID executionId);
}
