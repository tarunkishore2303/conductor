package com.loom.monitor.controller;

import com.loom.common.model.Task;
import com.loom.monitor.dto.SystemStatsResponse;
import com.loom.monitor.service.MonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/monitor")
@RequiredArgsConstructor
public class MonitorController {

    private final MonitorService monitorService;

    @GetMapping("/stats")
    public SystemStatsResponse stats() {
        return monitorService.getStats();
    }

    @GetMapping("/dlq")
    public Page<Task> dlq(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return monitorService.getDeadLettered(page, size);
    }
}
