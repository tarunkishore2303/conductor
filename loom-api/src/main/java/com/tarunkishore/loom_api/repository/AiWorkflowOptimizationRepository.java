package com.tarunkishore.loom_api.repository;
import com.tarunkishore.loom_api.ai.optimization.AiWorkflowOptimization;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AiWorkflowOptimizationRepository extends JpaRepository<AiWorkflowOptimization, UUID> {
    Optional<AiWorkflowOptimization> findByWorkflowIdAndContextHash(UUID workflowId, String contextHash);
    Optional<AiWorkflowOptimization> findFirstByWorkflowIdOrderByGeneratedAtDescIdDesc(UUID workflowId);
}
