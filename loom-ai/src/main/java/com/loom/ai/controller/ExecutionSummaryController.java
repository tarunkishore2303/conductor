package com.loom.ai.controller;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.ExecutionSummaryInterpretation;
import com.loom.ai.model.ExecutionSummaryRequest;
import com.loom.ai.service.ExecutionSummaryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/runs")
public class ExecutionSummaryController {
    private final ExecutionSummaryService service;
    private final AiProperties properties;

    public ExecutionSummaryController(ExecutionSummaryService service, AiProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/summarize")
    public SummaryResponse summarize(@Valid @RequestBody ExecutionSummaryRequest request) {
        return new SummaryResponse(service.summarize(request), properties.model());
    }

    public record SummaryResponse(ExecutionSummaryInterpretation interpretation, String model) {}
}
