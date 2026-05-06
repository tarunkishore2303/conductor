package com.loom.monitor.dto;

import java.util.Map;

public record SystemStatsResponse(
    Map<String, Long> jobsByStatus,
    Map<String, Long> tasksByStatus,
    long deadLetteredTasks
) {}
