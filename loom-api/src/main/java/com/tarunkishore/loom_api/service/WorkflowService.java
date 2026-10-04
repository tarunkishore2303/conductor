package com.tarunkishore.loom_api.service;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.loom.common.dto.JobSubmitRequest;
import com.loom.common.dto.TaskDefinition;
import com.loom.common.model.Job;
import com.loom.common.model.WorkflowTemplate;
import com.tarunkishore.loom_api.dto.WorkflowTemplateRequest;
import com.tarunkishore.loom_api.repository.WorkflowTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowTemplateRepository templateRepo;
    private final JobService jobService;
    private final DAGValidator dagValidator;
    private final ObjectMapper objectMapper;

    @Transactional
    public WorkflowTemplate createTemplate(WorkflowTemplateRequest request) {
        dagValidator.validate(request.tasks());

        String dagJson;
        try {
            dagJson = objectMapper.writeValueAsString(request.tasks());
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Failed to serialize DAG: " + e.getMessage());
        }

        WorkflowTemplate template = new WorkflowTemplate();
        template.setName(request.name());
        template.setDescription(request.description());
        template.setDagDefinitionJson(dagJson);
        template.setDefaultFailurePolicy(request.defaultFailurePolicy());
        return templateRepo.save(template);
    }

    @Transactional(readOnly = true)
    public WorkflowTemplate getTemplate(UUID templateId) {
        return templateRepo.findById(templateId)
            .orElseThrow(() -> new NoSuchElementException("Template not found: " + templateId));
    }

    @Transactional(readOnly = true)
    public List<TaskDefinition> parseTasks(WorkflowTemplate template) {
        try {
            return objectMapper.readValue(template.getDagDefinitionJson(),
                new TypeReference<>() {});
        } catch (JacksonException e) {
            throw new IllegalStateException("Corrupt template DAG: " + e.getMessage());
        }
    }

    @Transactional
    public Job submitJobFromTemplate(UUID templateId, String jobName) {
        WorkflowTemplate template = getTemplate(templateId);
        List<TaskDefinition> tasks = parseTasks(template);
        JobSubmitRequest request = new JobSubmitRequest(jobName, tasks, template.getDefaultFailurePolicy());
        return jobService.submit(request);
    }
}
