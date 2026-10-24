package com.tarunkishore.loom_api.controller;

import com.tarunkishore.loom_api.dto.JobResponse;
import com.tarunkishore.loom_api.dto.WorkflowTemplateRequest;
import com.tarunkishore.loom_api.dto.WorkflowTemplateResponse;
import com.tarunkishore.loom_api.service.WorkflowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @GetMapping
    public List<WorkflowTemplateResponse> list(@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return workflowService.listTemplates(limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowTemplateResponse create(@Valid @RequestBody WorkflowTemplateRequest request) {
        var template = workflowService.createTemplate(request);
        return WorkflowTemplateResponse.from(template, workflowService.parseTasks(template));
    }

    @GetMapping("/{templateId}")
    public WorkflowTemplateResponse get(@PathVariable UUID templateId) {
        var template = workflowService.getTemplate(templateId);
        return WorkflowTemplateResponse.from(template, workflowService.parseTasks(template));
    }

    @PostMapping("/{templateId}/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse submitJob(
            @PathVariable UUID templateId,
            @RequestBody Map<String, String> body) {
        String jobName = body.getOrDefault("name", "job-from-template");
        return JobResponse.from(workflowService.submitJobFromTemplate(templateId, jobName));
    }
}
