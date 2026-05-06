package com.loom.common.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_templates")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowTemplate {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    /** Serialized JSON of List<TaskDefinition>. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String dagDefinitionJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FailurePolicy defaultFailurePolicy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    @PrePersist
    void onPersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
