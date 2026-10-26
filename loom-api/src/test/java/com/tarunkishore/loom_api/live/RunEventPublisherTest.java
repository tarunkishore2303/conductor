package com.tarunkishore.loom_api.live;

import com.loom.common.model.JobStatus;
import com.loom.common.model.TaskStatus;
import com.tarunkishore.loom_api.live.RunStateReader.RunState;
import com.tarunkishore.loom_api.live.RunStateReader.TaskState;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunEventPublisherTest {
    private final RunEventPublisher publisher = new RunEventPublisher();
    private final UUID job = UUID.randomUUID(), task = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-26T10:00:00Z");

    private RunState state(JobStatus status, TaskStatus taskStatus, Instant retry) {
        return new RunState(job, status, now, Map.of(task, new TaskState(task, taskStatus, 0, now, retry, retry == null ? null : now)));
    }

    @Test void unchangedFactsDoNotCreateEvents() {
        var state = state(JobStatus.RUNNING, TaskStatus.PENDING, null);
        assertThat(publisher.changes(state, state)).isEmpty();
    }

    @Test void firstRetryIsRecognizedEvenWhenPendingStatusAndZeroRetryCounterDoNotChange() {
        var events = publisher.changes(state(JobStatus.RUNNING, TaskStatus.PENDING, null),
                state(JobStatus.RUNNING, TaskStatus.PENDING, now.plusSeconds(1)));
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(RunEvent.Type.TASK_RETRY_SCHEDULED);
            assertThat(event.retryCount()).isZero();
            assertThat(event.occurredAt()).isEqualTo(now);
        });
    }

    @Test void committedTaskAndJobCompletionProduceGroundedHints() {
        var events = publisher.changes(state(JobStatus.RUNNING, TaskStatus.PENDING, null),
                state(JobStatus.COMPLETE, TaskStatus.COMPLETE, null));
        assertThat(events).extracting(RunEvent::type).containsExactly(RunEvent.Type.TASK_COMPLETED, RunEvent.Type.JOB_COMPLETED);
        assertThat(events).allSatisfy(event -> assertThat(event.jobId()).isEqualTo(job));
        assertThat(events.getFirst().taskId()).isEqualTo(task);
    }

    @Test void dlqAndJobFailureRemainDistinct() {
        var events = publisher.changes(state(JobStatus.RUNNING, TaskStatus.PENDING, null),
                state(JobStatus.FAILED, TaskStatus.DEAD_LETTERED, null));
        assertThat(events).extracting(RunEvent::type).containsExactly(RunEvent.Type.TASK_DEAD_LETTERED, RunEvent.Type.JOB_FAILED);
    }

    @Test void onlyActuallyObservedRunningStateProducesTaskStarted() {
        var previous = state(JobStatus.RUNNING, TaskStatus.PENDING, null);
        assertThat(publisher.changes(previous, state(JobStatus.RUNNING, TaskStatus.COMPLETE, null)))
                .extracting(RunEvent::type).doesNotContain(RunEvent.Type.TASK_STARTED);
        assertThat(publisher.changes(previous, state(JobStatus.RUNNING, TaskStatus.RUNNING, null)))
                .extracting(RunEvent::type).containsExactly(RunEvent.Type.TASK_STARTED);
    }

    @Test void wireModelCannotCarryOutputConfigurationOrRawErrors() {
        String json = JsonMapper.builder().build().writeValueAsString(publisher.jobEvent(state(JobStatus.COMPLETE, TaskStatus.COMPLETE, null)));
        assertThat(json).doesNotContain("output", "error", "config", "name", "stack", "body");
        assertThat(RunEvent.class.getRecordComponents()).extracting(component -> component.getName())
                .containsExactly("eventId", "jobId", "taskId", "type", "occurredAt", "taskStatus", "jobStatus", "retryCount");
    }
}
