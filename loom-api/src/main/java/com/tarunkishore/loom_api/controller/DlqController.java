package com.tarunkishore.loom_api.controller;

import com.tarunkishore.loom_api.dto.DlqTaskResponse;
import com.tarunkishore.loom_api.service.DlqService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dlq")
@RequiredArgsConstructor
public class DlqController {

    private final DlqService dlqService;

    @GetMapping
    public Page<DlqTaskResponse> getDeadLettered(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return dlqService.getDeadLettered(page, size).map(DlqTaskResponse::from);
    }

    @PostMapping("/{taskId}/retry")
    public DlqTaskResponse retry(@PathVariable UUID taskId) {
        return DlqTaskResponse.from(dlqService.retryTask(taskId));
    }
}
