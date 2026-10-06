package com.tarunkishore.loom_api.repository;

import com.tarunkishore.loom_api.ai.AiFailureAnalysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface AiFailureAnalysisRepository extends JpaRepository<AiFailureAnalysis, UUID> {
    Optional<AiFailureAnalysis> findByJobIdAndContextHash(UUID jobId, String contextHash);

    Optional<AiFailureAnalysis> findFirstByJobIdOrderByGeneratedAtDesc(UUID jobId);
}
