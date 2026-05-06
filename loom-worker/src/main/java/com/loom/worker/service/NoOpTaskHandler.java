package com.loom.worker.service;

import com.loom.common.event.TaskEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NoOpTaskHandler implements TaskHandler {

    @Override
    public void execute(TaskEvent event) throws Exception {
        log.info("Executing task name={} taskId={} attempt={}", event.taskName(), event.taskId(), event.attemptNumber());
        // Simulate work — replace with real logic in production
        Thread.sleep(50);
    }
}
