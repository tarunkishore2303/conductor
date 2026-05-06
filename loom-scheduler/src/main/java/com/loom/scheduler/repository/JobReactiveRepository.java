package com.loom.scheduler.repository;

import com.loom.scheduler.model.JobRecord;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface JobReactiveRepository extends ReactiveCrudRepository<JobRecord, UUID> {

    @Modifying
    @Query("UPDATE jobs SET status = :status, version = version + 1, updated_at = NOW() WHERE id = :id AND version = :version")
    Mono<Integer> updateStatus(UUID id, String status, Long version);
}
