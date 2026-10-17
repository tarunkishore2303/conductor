package com.tarunkishore.loom_api.repository;

import com.loom.common.model.Task;
import com.loom.common.model.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    List<Task> findByJobId(UUID jobId);

    List<Task> findByJobIdAndStatus(UUID jobId, TaskStatus status);

    Page<Task> findByStatus(TaskStatus status, Pageable pageable);
    @Query("select t from Task t join fetch t.job where t.job.id in :jobIds order by t.job.id,t.id")
    List<Task> findOptimizationTasks(@Param("jobIds") List<UUID> jobIds, Pageable limit);

    @Query("select t.id as taskId,d.id as dependencyId from Task t join t.dependencies d where t.job.id in :jobIds order by t.id,d.id")
    List<DependencyEdge> findOptimizationDependencies(@Param("jobIds") List<UUID> jobIds, Pageable limit);

    interface DependencyEdge { UUID getTaskId(); UUID getDependencyId(); }
}
