package com.tarunkishore.loom_api.repository;

import com.loom.common.model.WorkflowTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface WorkflowTemplateRepository extends JpaRepository<WorkflowTemplate, UUID> {
    List<WorkflowTemplate> findAllBy(Pageable pageable);
}
