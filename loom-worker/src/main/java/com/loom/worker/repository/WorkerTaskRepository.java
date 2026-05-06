package com.loom.worker.repository;

import com.loom.common.model.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WorkerTaskRepository extends JpaRepository<Task, UUID> {

    @Query("SELECT t.job.status FROM Task t WHERE t.id = :taskId")
    Optional<String> findJobStatusByTaskId(@Param("taskId") UUID taskId);
}
