package com.tarunkishore.loom_api.ai.summary;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai/runs/{jobId}/summary")
public class ExecutionSummaryController {
    private final ExecutionSummaryService service;

    public ExecutionSummaryController(ExecutionSummaryService service) {
        this.service = service;
    }

    @PostMapping
    public ExecutionSummaryService.Summary generate(@PathVariable UUID jobId) {
        return service.generate(jobId);
    }

    @GetMapping
    public ExecutionSummaryService.Summary get(@PathVariable UUID jobId) {
        return service.get(jobId);
    }
}
