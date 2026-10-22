package com.loom.worker.repository;

import com.loom.common.model.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface WorkerTaskRepository extends JpaRepository<Task, UUID> {

    @Query("SELECT source FROM Task current JOIN current.dependencies source "
            + "WHERE current.id = :taskId AND current.job.id = :jobId "
            + "AND source.job.id = :jobId AND source.logicalId = :logicalId")
    List<Task> findDirectInputTasks(@Param("taskId") UUID taskId, @Param("jobId") UUID jobId,
            @Param("logicalId") String logicalId);

    @Query("SELECT t.job.status FROM Task t WHERE t.id = :taskId")
    Optional<String> findJobStatusByTaskId(@Param("taskId") UUID taskId);
}
