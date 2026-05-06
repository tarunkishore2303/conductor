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
