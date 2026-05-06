package com.loom.monitor.service;

import com.loom.common.model.Task;
import com.loom.common.model.TaskStatus;
import com.loom.monitor.dto.SystemStatsResponse;
import com.loom.monitor.repository.MonitorJobRepository;
import com.loom.monitor.repository.MonitorTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MonitorService {

    private final MonitorJobRepository jobRepo;
    private final MonitorTaskRepository taskRepo;

    @Transactional(readOnly = true)
    public SystemStatsResponse getStats() {
        Map<String, Long> jobStats = new LinkedHashMap<>();
        for (Object[] row : jobRepo.countByStatusGrouped()) {
            jobStats.put(row[0].toString(), (Long) row[1]);
        }

        Map<String, Long> taskStats = new LinkedHashMap<>();
        for (Object[] row : taskRepo.countByStatusGrouped()) {
            taskStats.put(row[0].toString(), (Long) row[1]);
        }

        long dlqCount = taskRepo.countByStatus(TaskStatus.DEAD_LETTERED);
        return new SystemStatsResponse(jobStats, taskStats, dlqCount);
    }

    @Transactional(readOnly = true)
    public Page<Task> getDeadLettered(int page, int size) {
        return taskRepo.findByStatus(TaskStatus.DEAD_LETTERED, PageRequest.of(page, size));
    }
}
