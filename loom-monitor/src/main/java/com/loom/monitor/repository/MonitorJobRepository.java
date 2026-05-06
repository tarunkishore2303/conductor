package com.loom.monitor.repository;

import com.loom.common.model.Job;
import com.loom.common.model.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface MonitorJobRepository extends JpaRepository<Job, UUID> {

    long countByStatus(JobStatus status);

    @Query("SELECT j.status, COUNT(j) FROM Job j GROUP BY j.status")
    List<Object[]> countByStatusGrouped();
}
