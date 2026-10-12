package com.tarunkishore.loom_api.ai.copilot;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai/copilot")
public class CopilotController {
    private final CopilotService service;
    public CopilotController(CopilotService service) { this.service = service; }
    @PostMapping("/query")
    public CopilotService.Response query(@RequestBody CopilotService.Query request) {
        return service.query(request);
    }
}
