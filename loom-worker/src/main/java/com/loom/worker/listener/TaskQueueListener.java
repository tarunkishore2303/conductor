package com.loom.worker.listener;

import com.loom.common.event.TaskEvent;
import com.loom.worker.service.TaskExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TaskQueueListener {

    private final TaskExecutorService executorService;

    @KafkaListener(topics = "task-queue", containerFactory = "taskQueueListenerFactory")
    public void onTask(TaskEvent event) {
        log.info("Received task taskId={} name={} attempt={}",
            event.taskId(), event.taskName(), event.attemptNumber());
        executorService.process(event);
    }
}
