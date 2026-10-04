package com.loom.ai.controller;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.GeneratedWorkflowProposal;
import com.loom.ai.service.WorkflowGenerationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/workflows")
public class WorkflowGenerationController {
    private final WorkflowGenerationService service;
    private final AiProperties properties;
    public WorkflowGenerationController(WorkflowGenerationService service, AiProperties properties) {
        this.service = service;
        this.properties = properties;
    }
    @PostMapping("/generate")
    public GenerationResponse generate(@Valid @RequestBody GenerationRequest request) {
        return new GenerationResponse(service.generate(request.prompt()), properties.model());
    }
    public record GenerationRequest(@NotBlank @Size(max = 4000) String prompt) {}
    public record GenerationResponse(GeneratedWorkflowProposal workflow, String model) {}
}
