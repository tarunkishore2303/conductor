package com.tarunkishore.loom_api.controller;

import com.tarunkishore.loom_api.ai.AiWorkflowProposalService;
import com.tarunkishore.loom_api.dto.WorkflowTemplateResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai/workflows")
public class AiWorkflowController {
    private final AiWorkflowProposalService service;

    public AiWorkflowController(AiWorkflowProposalService service) {
        this.service = service;
    }

    @PostMapping("/generate")
    public AiWorkflowProposalService.Preview generate(@RequestBody GenerationRequest request) {
        return service.generate(request.prompt());
    }

    @GetMapping("/proposals/{id}")
    public AiWorkflowProposalService.Preview get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/proposals/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowTemplateResponse approve(@PathVariable UUID id) {
        return service.approve(id);
    }

    public record GenerationRequest(String prompt) {}
}
