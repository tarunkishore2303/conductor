package com.loom.ai.controller;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.CopilotRequest;
import com.loom.ai.model.CopilotStep;
import com.loom.ai.service.CopilotService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/copilot")
public class CopilotController {
    private final CopilotService service;
    private final AiProperties properties;

    public CopilotController(CopilotService service, AiProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/step")
    public StepResponse step(@Valid @RequestBody CopilotRequest request) {
        return new StepResponse(service.step(request), properties.model());
    }

    public record StepResponse(CopilotStep step, String model) {}
}
