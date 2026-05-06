package com.tarunkishore.loom_api.service;

import com.loom.common.event.TaskEvent;
import com.loom.common.model.Task;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaskQueuePublisher {

    static final String TASK_QUEUE_TOPIC = "task-queue";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(Task task) {
        TaskEvent event = new TaskEvent(
            task.getJob().getId(),
            task.getId(),
            task.getName(),
            task.getMaxRetries(),
            0  // fresh attempt
        );
        kafkaTemplate.send(TASK_QUEUE_TOPIC, task.getId().toString(), event);
    }
}
