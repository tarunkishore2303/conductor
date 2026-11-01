package com.loom.scheduler.listener;

import com.loom.common.event.JobCreatedEvent;
import com.loom.scheduler.service.DAGScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class JobCreatedListener {
    private final DAGScheduler scheduler;

    @KafkaListener(topics = "job-created", containerFactory = "jobCreatedListenerFactory")
    public void onJobCreated(JobCreatedEvent event) {
        scheduler.scheduleJob(event.jobId()).block(Duration.ofSeconds(30));
    }
}
