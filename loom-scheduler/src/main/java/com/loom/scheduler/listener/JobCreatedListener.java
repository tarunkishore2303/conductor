package com.loom.scheduler.listener;

import com.loom.common.event.JobCreatedEvent;
import com.loom.scheduler.service.DAGScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobCreatedListener {

    private final DAGScheduler scheduler;

    @KafkaListener(
        topics = "job-created",
        containerFactory = "jobCreatedListenerFactory"
    )
    public void onJobCreated(JobCreatedEvent event) {
        log.info("Received job-created event for jobId={}", event.jobId());
        scheduler.scheduleJob(event.jobId())
            .doOnError(e -> log.error("Failed to schedule job {}: {}", event.jobId(), e.getMessage()))
            .subscribe();
    }
}
