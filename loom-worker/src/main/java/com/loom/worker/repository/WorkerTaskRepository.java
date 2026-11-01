package com.loom.worker.repository;

import com.loom.common.model.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface WorkerTaskRepository extends JpaRepository<Task, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Task t WHERE t.id = :taskId")
    Optional<Task> findLockedById(@Param("taskId") UUID taskId);

    @Query("SELECT source FROM Task current JOIN current.dependencies source "
            + "WHERE current.id = :taskId AND current.job.id = :jobId "
            + "AND source.job.id = :jobId AND source.logicalId = :logicalId")
    List<Task> findDirectInputTasks(@Param("taskId") UUID taskId, @Param("jobId") UUID jobId,
            @Param("logicalId") String logicalId);

    @Query("SELECT t.job.status FROM Task t WHERE t.id = :taskId")
    Optional<String> findJobStatusByTaskId(@Param("taskId") UUID taskId);
}
