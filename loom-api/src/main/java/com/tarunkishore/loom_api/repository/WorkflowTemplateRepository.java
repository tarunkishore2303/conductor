package com.tarunkishore.loom_api.repository;

import com.loom.common.model.WorkflowTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WorkflowTemplateRepository extends JpaRepository<WorkflowTemplate, UUID> {}
