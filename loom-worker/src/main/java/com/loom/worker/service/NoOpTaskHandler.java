package com.loom.worker.service;

import com.loom.common.event.TaskEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Slf4j
@Component
public class NoOpTaskHandler implements TaskHandler {

    private final boolean demoFailuresEnabled;

    public NoOpTaskHandler(@Value("${conductor.demo.failures-enabled:false}") boolean demoFailuresEnabled) {
        this.demoFailuresEnabled = demoFailuresEnabled;
    }

    @Override
    public void execute(TaskEvent event) throws Exception {
        log.info("Executing task name={} taskId={} attempt={}", event.taskName(), event.taskId(), event.attemptNumber());
        if (demoFailuresEnabled) {
            switch (event.taskName()) {
                case "demoConnectionTimeout" ->
                        throw new java.net.ConnectException("Demo dependency connection timed out");
                case "demoDownstreamTimeout" ->
                        throw new java.net.SocketTimeoutException("Demo downstream service response timed out");
                case "demoInvalidJson" ->
                        throw new IllegalArgumentException("Demo dependency returned invalid JSON");
                default -> { /* Regular NOOP tasks remain unchanged. */ }
            }
        }
        // Simulate work — replace with real logic in production
        Thread.sleep(50);
    }
}
