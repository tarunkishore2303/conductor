package com.tarunkishore.loom_api.service;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.loom.capability.TaskCapabilityValidators;
import com.loom.capability.TaskDataflowValidator;
import com.loom.common.dto.JobSubmitRequest;
import com.loom.common.dto.TaskDefinition;
import com.loom.common.model.Job;
import com.loom.common.model.WorkflowTemplate;
import com.tarunkishore.loom_api.dto.WorkflowTemplateRequest;
import com.tarunkishore.loom_api.dto.WorkflowTemplateResponse;
import com.tarunkishore.loom_api.repository.WorkflowTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowService {
    private final TaskDataflowValidator dataflowValidator = new TaskDataflowValidator();


    private final WorkflowTemplateRepository templateRepo;
    private final JobService jobService;
    private final DAGValidator dagValidator;
    private final ObjectMapper objectMapper;
    private final TaskCapabilityValidators capabilityValidators;

    @Transactional
    public WorkflowTemplate createTemplate(WorkflowTemplateRequest request) {
        request.tasks().forEach(task -> capabilityValidators.validate(task.type(), task.config()));
        dataflowValidator.validate(request.tasks().stream().map(task ->
                new TaskDataflowValidator.DataflowTask(task.taskId(), task.dependsOn(), task.config())).toList());
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
    public List<WorkflowTemplateResponse> listTemplates(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Workflow list limit must be between 1 and 100.");
        }
        var page = PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        return templateRepo.findAllBy(page).stream()
                .map(template -> WorkflowTemplateResponse.from(template, parseTasks(template))).toList();
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
        return jobService.submit(request, templateId);
    }
}
