package com.tarunkishore.loom_api.ai.optimization.domain;

import com.tarunkishore.loom_api.ai.optimization.client.WorkflowOptimizationClient;
import java.time.Instant;
import java.util.UUID;

public record OptimizationReport(
            UUID optimizationId,
            UUID workflowId,
            OptimizationFacts facts,
            WorkflowOptimizationClient.Interpretation interpretation,
            String model,
            Instant generatedAt,
            int optimizationVersion,
            String contextHash) {}
