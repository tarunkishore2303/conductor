package com.tarunkishore.loom_api.repository;

import com.loom.common.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    @Query("SELECT j FROM Job j LEFT JOIN FETCH j.tasks WHERE j.id = :id")
    Optional<Job> findByIdWithTasks(@Param("id") UUID id);
}
