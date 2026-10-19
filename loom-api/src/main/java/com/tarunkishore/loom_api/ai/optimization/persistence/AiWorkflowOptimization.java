package com.tarunkishore.loom_api.ai.optimization.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ai_workflow_optimizations")
@Getter
@Setter
public class AiWorkflowOptimization {
    @Id private UUID id;

    @Column(nullable = false)
    private UUID workflowId;

    @Column(nullable = false, length = 64)
    private String contextHash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String factsJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String interpretationJson;

    @Column(nullable = false, length = 128)
    private String model;

    @Column(nullable = false)
    private Instant generatedAt;

    @Column(nullable = false)
    private int optimizationVersion;
}
