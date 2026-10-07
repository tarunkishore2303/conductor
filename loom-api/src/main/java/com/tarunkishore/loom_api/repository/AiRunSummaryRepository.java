package com.tarunkishore.loom_api.repository;

import com.tarunkishore.loom_api.ai.summary.AiRunSummary;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface AiRunSummaryRepository extends JpaRepository<AiRunSummary, UUID> {
    Optional<AiRunSummary> findByJobIdAndContextHash(UUID jobId, String contextHash);

    Optional<AiRunSummary> findFirstByJobIdOrderByGeneratedAtDesc(UUID jobId);
}
