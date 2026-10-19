package com.tarunkishore.loom_api.repository;

import com.loom.common.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import com.loom.common.model.JobStatus;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    @Query("SELECT j FROM Job j LEFT JOIN FETCH j.tasks WHERE j.id = :id")
    Optional<Job> findByIdWithTasks(@Param("id") UUID id);
    @Query("select j from Job j where j.workflowTemplateId=:workflowId and j.status in :statuses and j.createdAt>=:since order by j.createdAt desc,j.id")
    List<Job> findOptimizationHistory(@Param("workflowId") UUID workflowId,
            @Param("statuses") List<JobStatus> statuses, @Param("since") Instant since, Pageable limit);
}
