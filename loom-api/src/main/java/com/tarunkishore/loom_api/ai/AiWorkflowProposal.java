package com.tarunkishore.loom_api.ai;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_workflow_proposals")
@Getter
@Setter
public class AiWorkflowProposal {
    @Id
    private UUID id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String workflowJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String validationErrorsJson;

    @Column(nullable = false, length = 128)
    private String model;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private UUID approvedWorkflowId;

    @Version
    private long version;
}
