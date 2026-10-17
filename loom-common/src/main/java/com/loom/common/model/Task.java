package com.loom.common.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import com.loom.capability.TaskType;

@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
public class Task {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.ColumnDefault("'NOOP'")
    @Column(name = "task_type", nullable = false, length = 32)
    private TaskType taskType = TaskType.NOOP;

    @org.hibernate.annotations.ColumnDefault("'{}'")
    @Column(name = "task_config_json", nullable = false, columnDefinition = "TEXT")
    private String taskConfigJson = "{}";

    /** Stable task-definition identity; null for historical runtime tasks. */
    @Column(name = "logical_id", columnDefinition = "TEXT")
    private String logicalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @Column(nullable = false)
    private int maxRetries;

    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant deadLetteredAt;

    @Version
    private Long version;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "task_dependencies",
        joinColumns = @JoinColumn(name = "task_id"),
        inverseJoinColumns = @JoinColumn(name = "depends_on_task_id")
    )
    private Set<Task> dependencies = new HashSet<>();

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
