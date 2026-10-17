package com.tarunkishore.loom_api.ai.optimization;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/ai/workflows/{workflowId}/optimization")
public class WorkflowOptimizationController {
    private final WorkflowOptimizationService service;
    public WorkflowOptimizationController(WorkflowOptimizationService service) { this.service = service; }
    @PostMapping public WorkflowOptimizationService.Report generate(@PathVariable UUID workflowId) { return service.generate(workflowId); }
    @GetMapping public WorkflowOptimizationService.Report get(@PathVariable UUID workflowId) { return service.get(workflowId); }
}
