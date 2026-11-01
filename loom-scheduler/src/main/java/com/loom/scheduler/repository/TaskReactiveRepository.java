package com.loom.scheduler.repository;

import com.loom.common.model.TaskStatus;
import com.loom.scheduler.model.TaskRecord;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TaskReactiveRepository extends ReactiveCrudRepository<TaskRecord, UUID> {

    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM task_executions e JOIN tasks t ON t.id = e.task_id
            WHERE e.execution_id = :executionId AND t.id = :taskId AND t.job_id = :jobId
              AND e.execution_generation = t.execution_generation
              AND t.status = :taskStatus AND e.status = :executionStatus
        )
        """)
    Mono<Boolean> matchesCurrentExecution(UUID jobId, UUID taskId, UUID executionId,
            String taskStatus, String executionStatus);

    Flux<TaskRecord> findByJobId(UUID jobId);

    @Query("SELECT depends_on_task_id FROM task_dependencies WHERE task_id = :taskId")
    Flux<UUID> findDependencyIds(UUID taskId);

    /** Find tasks that have taskId as a dependency (downstream tasks). */
    @Query("SELECT task_id FROM task_dependencies WHERE depends_on_task_id = :taskId")
    Flux<UUID> findDownstreamTaskIds(UUID taskId);

    @Modifying
    @Query("UPDATE tasks SET status = :status, version = version + 1, updated_at = NOW() WHERE id = :id AND version = :version")
    Mono<Integer> updateStatus(UUID id, String status, Long version);
}
