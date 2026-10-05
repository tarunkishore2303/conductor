package com.tarunkishore.loom_api.repository;

import com.loom.common.model.TaskExecution;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface FailureEvidenceRepository extends JpaRepository<TaskExecution, UUID> {
    @Query(
            "select e from TaskExecution e join fetch e.task t where t.job.id=:jobId order by"
                + " t.id,e.startedAt,e.id")
    List<TaskExecution> findForJob(@Param("jobId") UUID jobId, Pageable limit);
}
