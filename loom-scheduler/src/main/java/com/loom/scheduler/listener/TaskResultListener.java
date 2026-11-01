package com.loom.scheduler.listener;

import com.loom.common.event.TaskResultEvent;
import com.loom.scheduler.service.DAGScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class TaskResultListener {
    private final DAGScheduler scheduler;

    @KafkaListener(topics = "task-results", containerFactory = "taskResultListenerFactory")
    public void onTaskResult(TaskResultEvent event) {
        // Do not return (and acknowledge Kafka) until PostgreSQL commits. Exceptions
        // propagate to the listener container's retry handler rather than disappearing.
        scheduler.onTaskResult(event).block(Duration.ofSeconds(30));
    }
}
