package com.tarunkishore.loom_api.service;

import com.loom.common.event.JobCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JobEventPublisher {

    static final String JOB_CREATED_TOPIC = "job-created";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishJobCreated(UUID jobId) {
        kafkaTemplate.send(JOB_CREATED_TOPIC, jobId.toString(), new JobCreatedEvent(jobId));
    }
}
