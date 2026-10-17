package com.loom.ai.controller;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.*;
import com.loom.ai.service.WorkflowOptimizationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/workflows")
public class WorkflowOptimizationController {
    private final WorkflowOptimizationService service;
    private final AiProperties properties;
    public WorkflowOptimizationController(WorkflowOptimizationService service, AiProperties properties) {
        this.service = service; this.properties = properties;
    }
    @PostMapping("/interpret-optimization")
    public Response interpret(@Valid @RequestBody WorkflowOptimizationRequest request) {
        return new Response(service.interpret(request), properties.model());
    }
    public record Response(OptimizationInterpretation interpretation, String model) {}
}
