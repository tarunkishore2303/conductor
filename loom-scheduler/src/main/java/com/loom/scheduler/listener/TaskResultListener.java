package com.loom.scheduler.listener;

import com.loom.common.event.TaskResultEvent;
import com.loom.common.model.TaskStatus;
import com.loom.scheduler.service.DAGScheduler;
import com.loom.scheduler.repository.TaskReactiveRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class TaskResultListener {

    private final DAGScheduler scheduler;
    private final TaskReactiveRepository taskRepo;

    @KafkaListener(
        topics = "task-results",
        containerFactory = "taskResultListenerFactory"
    )
    public void onTaskResult(TaskResultEvent event) {
        log.info("Received task result: taskId={} status={}", event.taskId(), event.status());
        taskRepo.findById(event.taskId())
            .flatMap(task -> taskRepo.updateStatus(task.id(), event.status().name(), task.version())
                .thenReturn(task))
            .flatMap(task -> {
                if (event.status() == TaskStatus.COMPLETE) {
                    return scheduler.onTaskComplete(event.jobId(), event.taskId());
                } else if (event.status() == TaskStatus.DEAD_LETTERED) {
                    return scheduler.onTaskDeadLettered(event.jobId());
                }
                return Mono.empty();
            })
            .doOnError(e -> log.error("Error processing task result {}: {}", event.taskId(), e.getMessage()))
            .subscribe();
    }
}
