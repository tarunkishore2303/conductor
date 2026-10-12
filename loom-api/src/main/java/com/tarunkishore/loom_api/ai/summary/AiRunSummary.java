package com.tarunkishore.loom_api.ai.summary;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_run_summaries")
@Getter
@Setter
public class AiRunSummary {
    @Id private UUID id;

    @Column(nullable = false)
    private UUID jobId;

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
    private int summaryVersion;
}
