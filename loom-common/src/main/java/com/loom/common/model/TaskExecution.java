package com.loom.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task_executions")
@Getter
@Setter
@NoArgsConstructor
public class TaskExecution {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    /** Idempotency key — worker writes this before running; checked on retry. */
    @Column(nullable = false, unique = true)
    private UUID executionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @Column(nullable = false, updatable = false)
    private Instant startedAt;

    private Instant completedAt;

    /** Zero-based attempt number; null for records created before evidence capture. */
    private Integer attemptNumber;

    @Column(length = 255)
    private String errorType;

    @Column(length = 2048)
    private String errorMessage;

    private Instant retryScheduledAt;

    /** Explicitly captured successful output; excluded from ordinary entity serialization. */
    @JsonIgnore
    @Column(name = "output_text", columnDefinition = "TEXT")
    private String outputText;

    @JsonIgnore
    @Column(name = "output_content_type", length = 32)
    private String outputContentType;

    @PrePersist
    void onPersist() {
        if (id == null) id = UUID.randomUUID();
        startedAt = Instant.now();
    }
}
