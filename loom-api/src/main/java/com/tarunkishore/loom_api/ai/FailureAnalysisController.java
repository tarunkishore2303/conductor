package com.tarunkishore.loom_api.ai;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai/runs")
public class FailureAnalysisController {
    private final FailureAnalysisService service;

    public FailureAnalysisController(FailureAnalysisService service) {
        this.service = service;
    }

    @PostMapping("/{jobId}/analyze-failure")
    public FailureAnalysisService.Analysis analyze(@PathVariable UUID jobId) {
        return service.analyze(jobId);
    }

    @GetMapping("/{jobId}/failure-analysis")
    public FailureAnalysisService.Analysis get(@PathVariable UUID jobId) {
        return service.get(jobId);
    }
}
