package com.loom.ai.controller;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.FailureFactsRequest;
import com.loom.ai.model.FailureInterpretation;
import com.loom.ai.service.FailureInterpretationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/failures")
public class FailureInterpretationController {
    private final FailureInterpretationService service;
    private final AiProperties properties;

    public FailureInterpretationController(FailureInterpretationService service, AiProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/analyze")
    public AnalysisResponse analyze(@Valid @RequestBody FailureFactsRequest request) {
        return new AnalysisResponse(service.analyze(request), properties.model());
    }

    public record AnalysisResponse(FailureInterpretation interpretation, String model) {}
}
