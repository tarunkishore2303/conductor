package com.tarunkishore.loom_api.ai.optimization.api;

import com.tarunkishore.loom_api.ai.optimization.application.WorkflowOptimizationService;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationReport;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/ai/workflows/{workflowId}/optimization")
public class WorkflowOptimizationController {
    private final WorkflowOptimizationService service;
    public WorkflowOptimizationController(WorkflowOptimizationService service) { this.service = service; }
    @PostMapping public OptimizationReport generate(@PathVariable UUID workflowId) { return service.generate(workflowId); }
    @GetMapping public OptimizationReport get(@PathVariable UUID workflowId) { return service.get(workflowId); }
}
